package com.pms.pms_trade_capture.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import com.pms.pms_trade_capture.domain.PendingStreamMessage;
import com.pms.pms_trade_capture.stream.StreamConsumerManager;
import com.rabbitmq.stream.MessageHandler;
import com.pms.rttm.client.clients.RttmClient;
import com.pms.rttm.client.dto.ErrorEventPayload;
import com.pms.rttm.client.enums.EventStage;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;

@Service
public class BatchingIngestService implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(BatchingIngestService.class);

    private final BatchPersistenceService persistenceService;
    private final StreamOffsetManager offsetManager;
    private final StreamConsumerManager consumerManager;
    private final ScheduledExecutorService scheduler;
    private final RttmClient rttmClient;

    private final ReentrantLock bufferLock = new ReentrantLock();

    private final BlockingQueue<PendingStreamMessage> messageBuffer = new LinkedBlockingQueue<>(10000);

    @Value("${app.ingest.batch.max-size:500}")
    private int maxBatchSize;

    @Value("${app.ingest.batch.flush-interval-ms:100}")
    private long flushIntervalMs;

    @Value("${spring.application.name}")
    private String serviceName;

    // Single Ordered Buffer (Fixes Offset Gap Bug)
    // private final List<PendingStreamMessage> currentBatch = new ArrayList<>();
    // private final Object batchLock = new Object();

    // Handle to the running task, so we can cancel it specifically
    private volatile ScheduledFuture<?> flushTask;
    private volatile boolean running = false;

    public BatchingIngestService(
            BatchPersistenceService persistenceService,
            StreamOffsetManager offsetManager,
            @Qualifier("ingestScheduler") ScheduledExecutorService scheduler,
            @Lazy StreamConsumerManager consumerManager,
            RttmClient rttmClient) {
        this.consumerManager = consumerManager;
        this.persistenceService = persistenceService;
        this.offsetManager = offsetManager;
        this.scheduler = scheduler;
        this.rttmClient = rttmClient;
    }

    @Override
    public void start() {
        log.info("Starting Batching Ingest Task...");

        // Schedule the task on the injected executor
        this.flushTask = scheduler.scheduleWithFixedDelay(
                this::flushOnTime,
                flushIntervalMs,
                flushIntervalMs / 2,
                TimeUnit.MILLISECONDS);

        this.running = true;
        log.info("Batching Ingest Task scheduled.");
    }

    @Override
    public void stop() {
        log.info("Stopping Batching Ingest Task...");
        this.running = false;

        // 1. Cancel the periodic task (Don't shutdown the executor, it's a shared bean)
        if (flushTask != null) {
            flushTask.cancel(false); // false = let it finish if currently running
        }

        // 2. CRITICAL: Manual Final Flush to drain buffer
        flushBatch("Shutdown-Drain");

        log.info("Batching Ingest Task stopped and buffer drained.");
    }

    @Override
    public boolean isRunning() {
        return this.running;
    }

    @Override
    public boolean isAutoStartup() {
        return SmartLifecycle.super.isAutoStartup();
    }

    @Override
    public int getPhase() {
        // Stop AFTER Consumer (MAX), but BEFORE Spring destroys the 'ingestScheduler'
        // bean (0)
        return Integer.MAX_VALUE - 1000;
    }

    /**
     * Add message to buffer with timeout-based backpressure.
     * If buffer is full after timeout, route message directly to DLQ to prevent
     * data loss.
     * This ensures we never block the RabbitMQ Stream consumer indefinitely.
     */
    public void addMessage(PendingStreamMessage message) {
        if (messageBuffer.offer(message)) {
            return;
        }

        log.warn("⚠️ Buffer full! Pausing consumer and BLOCKING. Offset: {}", message.getOffset());
        consumerManager.pause(); //! Backpressure: Pause consumer until we clear space

        try {
            messageBuffer.put(message); // Blocks here until flushBatch clears space
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Thread interrupted during backpressure wait.", e);
            persistenceService.saveToDlq(message, "App Shutdown/Interrupted");
        }
    }

    private void flushOnTime() {
        if (!running || messageBuffer.isEmpty())
            return;
        flushBatch("Time-Threshold");
    }

    private void flushBatch(String trigger) {
        bufferLock.lock();
        List<PendingStreamMessage> batchToProcess = new ArrayList<>();
        try {
            messageBuffer.drainTo(batchToProcess, 500);
        } finally {
            bufferLock.unlock();
        }

        if (batchToProcess.isEmpty())
            return;

        // --- RETRY LOOP FOR SYSTEM OUTAGES ---
        boolean processed = false;
        while (!processed) {
            try {
                processBatchLogic(batchToProcess);
                processed = true; // Success

                // Resume consumer if we cleared enough space
                if (messageBuffer.size() < 1000)
                    consumerManager.resume();

            } catch (CallNotPermittedException e) {
                // CIRCUIT OPEN: DB is Dead.
                log.error("CIRCUIT OPEN: Pausing Consumer & Waiting 5s...");
                consumerManager.pause();
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                // Continue loop -> Retry same batch

            } catch (Exception e) {
                log.error("Fatal Unexpected Error in Flush Loop", e);
                processed = true; // Abort to prevent infinite loop on bugs
            }
        }
    }

    private void processBatchLogic(List<PendingStreamMessage> batch) {
        // Safety: Empty batch check (defensive programming)
        if (batch == null || batch.isEmpty()) {
            log.warn("processBatchLogic called with empty batch. Skipping.");
            return;
        }

        try {
            // 1. FAST PATH (Batch)
            persistenceService.persistBatch(batch);
            
            // CRITICAL: Commit offset for the LAST message in batch
            // This advances RabbitMQ stream regardless of validity
            // Invalid messages are in SafeStore + DLQ, NOT in Outbox (correct behavior)
            commitOffset(batch.get(batch.size() - 1));

        } catch (CallNotPermittedException e) {
            throw e; // Propagate to retry loop

        } catch (Exception e) {
            log.warn("Batch Failed. Switching to Safe Path. Error: {}", e.getMessage());
            sendErrorEvent("Batch persistence failed", e.getMessage());

            // 2. SAFE PATH (Single Item Fallback)
            PendingStreamMessage lastSuccess = null;

            for (PendingStreamMessage msg : batch) {
                try {
                    persistenceService.persistSingleSafely(msg);
                    lastSuccess = msg; // Track progress regardless of DLQ or DB Success
                } catch (CallNotPermittedException cbEx) {
                    throw cbEx; // DB died mid-loop -> Stop & Retry
                } catch (Exception ex) {
                    log.error("Unexpected error in safe path", ex);
                    sendErrorEvent("Single item persistence failed", ex.getMessage());
                }
            }

            // Commit offset for last successfully processed message (or last attempted)
            if (lastSuccess != null) {
                commitOffset(lastSuccess);
            }
        }
    }

    private void commitOffset(PendingStreamMessage msg) {
        MessageHandler.Context context = msg.getContext();
        if (context != null) {
            context.storeOffset();
        }
    }

    /**
     * Send error event to RTTM when ingestion fails
     */
    private void sendErrorEvent(String errorType, String errorMessage) {
        try {
            ErrorEventPayload errorEvent = ErrorEventPayload.builder()
                    .serviceName(serviceName)
                    .errorType(errorType)
                    .errorMessage(errorMessage)
                    .eventStage(EventStage.INGESTED)
                    .build();

            rttmClient.sendErrorEvent(errorEvent);
            log.debug("Sent error event to RTTM: {}", errorType);
        } catch (Exception ex) {
            log.warn("Failed to send error event to RTTM: {}", ex.getMessage());
        }
    }
}
