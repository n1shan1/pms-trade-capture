package com.pms.pms_trade_capture.outbox;

import com.pms.pms_trade_capture.domain.DlqEntry;
import com.pms.pms_trade_capture.domain.OutboxEvent;
import com.pms.pms_trade_capture.repository.DlqRepository;
import com.pms.pms_trade_capture.repository.OutboxRepository;
import lombok.SneakyThrows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.Executor;

@Component
public class OutboxDispatcher implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxRepository outboxRepo;
    private final DlqRepository dlqRepo;
    private final OutboxEventProcessor processor;
    private final AdaptiveBatchSizer batchSizer;
    private final Executor taskExecutor;
    private final TransactionTemplate transactionTemplate; // From AppInfraConfig

    @Value("${app.outbox.max-retries:3}")
    private int maxRetries;

    private volatile boolean running = false;

    public OutboxDispatcher(OutboxRepository outboxRepo,
            DlqRepository dlqRepo,
            OutboxEventProcessor processor,
            AdaptiveBatchSizer batchSizer,
            @Qualifier("outboxExecutor") Executor taskExecutor,
            TransactionTemplate transactionTemplate) {
        this.outboxRepo = outboxRepo;
        this.processor = processor;
        this.dlqRepo = dlqRepo;
        this.batchSizer = batchSizer;
        this.taskExecutor = taskExecutor;
        this.transactionTemplate = transactionTemplate;
    }

    @SneakyThrows
    @Override
    public void start() {
        if (running)
            return;
        log.info("Starting Adaptive Outbox Dispatcher...");
        running = true;

        // Submit the long-running loop to the Spring-managed thread pool
        taskExecutor.execute(this::dispatchLoop);
    }

    @Override
    public void stop() {
        log.info("Stopping Outbox Dispatcher...");
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return SmartLifecycle.super.isAutoStartup();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1000;
    }

    /**
     * Single-Threaded Loop to ensure Strict Ordering.
     */
    private void dispatchLoop() {
        while(running){
            try {
                long startTime = System.currentTimeMillis();
                int limit = batchSizer.getCurrentSize();

                // 1. Fetch
                // Uses SKIP LOCKED, so multiple pods can run this logic safely in parallel.
                List<OutboxEvent> batch = outboxRepo.findPendingBatch(limit);

                if (batch.isEmpty()) {
                    batchSizer.reset(); // No load -> reset to min
                    sleep(50); // Prevent tight loop
                    continue;
                }
                // 2. Process (Strict Serial Order)
                for (OutboxEvent event : batch) {
                    if (!running) break;
                    processWithRetry(event);
                }

                // 3. Feedback
                long duration = System.currentTimeMillis() - startTime;
                batchSizer.adjust(duration, batch.size());
            } catch (Exception e){
                throw new RuntimeException(e);
            }
        }
    }

    private void processWithRetry(OutboxEvent event) {
        try {
            // Attempt to send
            processor.process(event);
        } catch (Exception e) {
            handleFailure(event, e);
        }
    }

    private void handleFailure(OutboxEvent event, Exception e) {
        int attempts = event.getAttempts() + 1;
        event.setAttempts(attempts);
        log.warn("Failed to publish event {}. Attempt {}/{}. Error: {}", event.getId(), attempts, maxRetries,
                e.getMessage());

        if (attempts >= maxRetries) {
            // 1. Max Retries Reached -> Move to DLQ
            moveToDlq(event, e.getMessage());
        } else {
            // 2. Retry Later -> Update attempts in DB and release lock
            outboxRepo.save(event);
        }
    }

    private void moveToDlq(OutboxEvent event, String errorMsg) {
        transactionTemplate.execute(status -> {
            log.error("Moving event {} to DLQ after {} failed attempts.", event.getId(), maxRetries);

            DlqEntry dlqEntry = new DlqEntry(event.getPayload(), "Outbox Failure: " + errorMsg);
            dlqRepo.save(dlqEntry);

            // Remove from Outbox so we don't try again
            outboxRepo.delete(event);
            return null;
        });
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }
}
