package com.pms.pms_trade_capture.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

@Component
public class AdaptiveBatchSizer {
    private static final Logger log = LoggerFactory.getLogger(AdaptiveBatchSizer.class);

    @Value("${app.outbox.target-latency-ms:200}")
    private long targetLatencyMs;

    @Value("${app.outbox.min-batch:10}")
    private int minBatchSize;

    @Value("${app.outbox.max-batch:2000}")
    private int maxBatchSize;

    private final AtomicInteger currentBatchSize = new AtomicInteger(10);

    /**
     * Calculates the next batch size based on the performance of the previous batch.
     * * @param timeTakenMs Total time taken to process the batch (DB Fetch + Kafka Send + DB Update)
     * @param recordsProcessed Number of records in that batch
     */

    public void adjust(Long timeTakenMs, int recordsProcessed){
        int current = currentBatchSize.get();
        int next = current;

        // 1. DRAIN PHASE: The queue is empty or near-empty.
        // We asked for 'current' but got fewer. We are catching up.
        // Reset to minimum to ensure the lowest latency for the next single trade.
        if (recordsProcessed < current) {
            next = minBatchSize;
        }
        // 2. GROWTH PHASE: We are full. Check performance.
        else {
            if (timeTakenMs < targetLatencyMs) {
                // FAST: We are under budget (e.g. 50ms vs 200ms target).
                // Additive Increase: Grow slowly to find the limit.
                next = (int) (current * 1.2);
                next = Math.min(next, maxBatchSize);
            } else {
                // SLOW: We exceeded budget. The DB or Kafka is pushing back.
                // Multiplicative Decrease: Back off quickly to stabilize.
                next = (int) (current * 0.7);
                next = Math.max(next, minBatchSize);
            }
        }
        // Only update/log if changed significantly to reduce noise
        if (next != current) {
            currentBatchSize.set(next);
            log.debug("Adaptive Batch: Latency={}ms, Count={}, Adjustment: {} -> {}",
                    timeTakenMs, recordsProcessed, current, next);
        }

    }

    public int getCurrentSize() {
        return currentBatchSize.get();
    }

    public void reset() {
        currentBatchSize.set(minBatchSize);
    }
}
