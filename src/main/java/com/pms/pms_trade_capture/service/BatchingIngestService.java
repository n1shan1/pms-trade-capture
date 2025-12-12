package com.pms.pms_trade_capture.service;


import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;

import com.pms.pms_trade_capture.domain.PendingStreamMessage;

@Service
public class BatchingIngestService implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(BatchingIngestService.class);

    private final BatchPersistenceService persistenceService;
    private final StreamOffsetManager offsetManager;
    private final ScheduledExecutorService scheduler;

    @Value("${app.ingest.batch.max-size:500}")
    private int maxBatchSize;

    @Value("${app.ingest.batch.flush-interval-ms:100}")
    private long flushIntervalMs;

    // Single Ordered Buffer (Fixes Offset Gap Bug)
    private final List<PendingStreamMessage> currentBatch = new ArrayList<>();
    private final Object batchLock = new Object();

    // Handle to the running task, so we can cancel it specifically
    private volatile ScheduledFuture<?> flushTask;
    private volatile boolean running = false;

    public BatchingIngestService(BatchPersistenceService persistenceService,
                                 StreamOffsetManager offsetManager, @Qualifier("ingestScheduler") ScheduledExecutorService scheduler) {
        this.persistenceService = persistenceService;
        this.offsetManager = offsetManager;
        this.scheduler = scheduler;
    }

    @Override
    public void start() {
        log.info("Starting Batching Ingest Task...");

        // Schedule the task on the injected executor
        this.flushTask = scheduler.scheduleWithFixedDelay(
                this::flushOnTime,
                flushIntervalMs,
                flushIntervalMs / 2,
                TimeUnit.MILLISECONDS
        );

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
        // Stop AFTER Consumer (MAX), but BEFORE Spring destroys the 'ingestScheduler' bean (0)
        return Integer.MAX_VALUE - 1000;
    }

    public void addMessage(PendingStreamMessage message) {
        synchronized (batchLock) {
            currentBatch.add(message);
            if (currentBatch.size() >= maxBatchSize) {
                flushBatch("Size-Threshold");
            }
        }
    }

    private void flushOnTime() {
        if (!running) return;
        synchronized (batchLock) {
            if (!currentBatch.isEmpty()) {
                flushBatch("Time-Threshold");
            }
        }
    }

    /**
     * Optimized Flush:
     * 1. Acquire Lock -> Swap List -> Release Lock (Fast)
     * 2. Persist to DB -> Commit Offset (Slow, but Non-Blocking)
     */
    private void flushBatch(String trigger) {
        List<PendingStreamMessage> batchToProcess;

        // Critical Section: Fast Reference Swap
        synchronized (batchLock) {
            if (currentBatch.isEmpty()) return;

            // Snapshot the buffer
            batchToProcess = new ArrayList<>(currentBatch);
            // Clear the main buffer so Consumer can keep adding to it immediately
            currentBatch.clear();
        }

        // Long-running DB operation happens HERE (Concurrent with new messages arriving)
        processBatch(batchToProcess, trigger);
    }

    private void processBatch(List<PendingStreamMessage> batch, String trigger) {
        try {
            long highestOffset = batch.get(batch.size() - 1).getOffset();
            log.debug("Flushing {} trades. Trigger: {}", batch.size(), trigger);

            boolean success = persistenceService.persistBatch(batch);

            if (success) {
                offsetManager.commit(highestOffset);
            } else {
                persistenceService.persistToDlq(batch, "Batch Persistence Failed");
                offsetManager.commit(highestOffset);
            }
        } catch (Exception e) {
            log.error("Unexpected error during batch processing", e);
        }
    }
}

