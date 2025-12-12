package com.pms.pms_trade_capture.outbox;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.google.protobuf.InvalidProtocolBufferException;
import com.pms.pms_trade_capture.domain.OutboxEvent;
import com.pms.pms_trade_capture.repository.OutboxRepository;

import lombok.SneakyThrows;

@Component
public class OutboxDispatcher implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxRepository outboxRepo;
    private final OutboxEventProcessor processor;
    private final AdaptiveBatchSizer batchSizer;
    private final Executor taskExecutor; // From AppInfraConfig

    private volatile boolean running = false;

    public OutboxDispatcher(OutboxRepository outboxRepo,
                            OutboxEventProcessor processor,
                            AdaptiveBatchSizer batchSizer,
                            @Qualifier("outboxExecutor") Executor taskExecutor) {
        this.outboxRepo = outboxRepo;
        this.processor = processor;
        this.batchSizer = batchSizer;
        this.taskExecutor = taskExecutor;
    }


    @SneakyThrows
    @Override
    public void start() {
        if (running) return;
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

                if(batch.isEmpty()){
                    batchSizer.reset(); // No load -> reset to min
                    sleep(50); // Prevent tight loop
                    continue;
                }
                // 2. Process (Strict Serial Order)
                for (OutboxEvent event : batch) {
                    if (!running) break;
                    processor.process(event);
                }

                // 3. Feedback
                long duration = System.currentTimeMillis() - startTime;
                batchSizer.adjust(duration, batch.size());
            } catch (InterruptedException ie) {
                // FIX: Specific catch for InterruptedException to handle shutdown gracefully
                log.info("Outbox Dispatcher interrupted, stopping loop.");
                Thread.currentThread().interrupt();
                running = false;
            } catch (InvalidProtocolBufferException | ExecutionException e) {
                log.error("Error in dispatch loop", e);
                batchSizer.reset(); // Back off on error
                sleep(1000);
            }
        }
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
