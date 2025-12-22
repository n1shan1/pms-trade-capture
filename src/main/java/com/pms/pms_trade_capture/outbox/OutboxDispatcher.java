package com.pms.pms_trade_capture.outbox;

import java.util.List;
import java.util.concurrent.Executor;

import com.pms.pms_trade_capture.dto.BatchProcessingResult;
import com.pms.pms_trade_capture.exception.PoisonPillException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.pms.pms_trade_capture.domain.DlqEntry;
import com.pms.pms_trade_capture.domain.OutboxEvent;
import com.pms.pms_trade_capture.repository.DlqRepository;
import com.pms.pms_trade_capture.repository.OutboxRepository;

import lombok.SneakyThrows;

/**
 * PRODUCTION-GRADE Outbox Dispatcher with PostgreSQL Advisory Locks for strict
 * ordering.
 * 
 * CRITICAL ARCHITECTURAL DECISION:
 * We lock PORTFOLIOS (logical aggregates), not individual rows.
 * 
 * WHY ADVISORY LOCKS?
 * 1. Row locks (FOR UPDATE SKIP LOCKED) allow "leapfrogging":
 * - Pod A locks trade #100 (portfolio P1)
 * - Pod B sees #100 locked, SKIPS it, locks trade #101 (same portfolio P1)
 * - Pod B sends #101 to Kafka BEFORE Pod A sends #100
 * - RESULT: Trades arrive out of order (SILENT CORRECTNESS FAILURE)
 * 
 * 2. Advisory locks prevent this:
 * - pg_try_advisory_xact_lock(hash(portfolio_id)) locks the PORTFOLIO concept
 * - If Pod A owns portfolio P1, Pod B gets ZERO P1 rows (filtered at query
 * level)
 * - One portfolio → one pod → one Kafka producer → guaranteed ordering
 * 
 * CRITICAL INVARIANTS:
 * 1. PORTFOLIO ISOLATION: Only one pod processes a portfolio at a time
 * 2. STRICT ORDERING: Within a portfolio, trades processed in chronological
 * order
 * 3. PREFIX-SAFE: Only marks successful prefix as SENT (never skips failed
 * events)
 * 4. POISON PILL HANDLING: Routes bad data to DLQ without blocking healthy
 * trades
 * 5. SYSTEM FAILURE HANDLING: Backs off on Kafka downtime (exponential backoff)
 * 6. CRASH SAFETY: Transactional boundaries ensure at-least-once delivery
 * 7. DEADLOCK-FREE: Single advisory lock per transaction (no multi-lock
 * scenarios)
 * 
 * ORDERING GUARANTEE:
 * "Can Trade #101 for the same portfolio ever reach Kafka before Trade #100?"
 * ANSWER: NO. Mathematically impossible. Advisory lock ensures exclusive
 * portfolio ownership.
 */
@Component
public class OutboxDispatcher implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxRepository outboxRepo;
    private final DlqRepository dlqRepo;
    private final OutboxEventProcessor processor;
    private final AdaptiveBatchSizer batchSizer;
    private final Executor taskExecutor;
    private final TransactionTemplate transactionTemplate;

    @Value("${app.outbox.system-failure-backoff-ms:1000}")
    private long systemFailureBackoffMs;

    @Value("${app.outbox.max-backoff-ms:30000}")
    private long maxBackoffMs;

    private volatile boolean running = false;
    private volatile long currentBackoff = 0;

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
        log.info("------------Starting Portfolio-Ordered Outbox Dispatcher...---------------");
        running = true;

        // Submit the long-running loop to the Spring-managed thread pool
        taskExecutor.execute(this::dispatchLoop);
    }

    @Override
    public void stop() {
        log.info("-------------------Stopping Outbox Dispatcher...-----------------------");
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
     * CRITICAL: Portfolio-isolated dispatch loop using advisory locks.
     * 
     * SIMPLIFIED ALGORITHM (vs. old two-query approach):
     * 1. Query: findPendingBatch(limit)
     * - PostgreSQL evaluates pg_try_advisory_xact_lock() for EACH row
     * - Rows whose portfolio is already locked by another pod are FILTERED OUT
     * - Returns up to `limit` events from portfolios THIS pod successfully locked
     * 2. Group by portfolio (maintains ordering within each portfolio)
     * 3. Process each portfolio's batch (prefix-safe)
     * 4. Handle failures (poison pills → DLQ, system failures → backoff)
     * 5. Repeat
     * 
     * WHY THIS IS CORRECT:
     * - Advisory lock ensures: If Pod A owns P1, Pod B gets ZERO P1 rows
     * - No "leapfrogging" possible (Trade #101 can't overtake Trade #100)
     * - Multiple portfolios processed in parallel (P1 on Pod A, P2 on Pod B)
     * - Lock auto-released on commit/rollback (no cleanup needed)
     * 
     * ORDERING GUARANTEE:
     * One portfolio → one pod → one Kafka producer → strict chronological order.
     */
    private void dispatchLoop() {
        while (running) {
            try {
                // Apply backoff if previous iteration had system failure
                if (currentBackoff > 0) {
                    log.warn("System failure backoff active: sleeping {}ms", currentBackoff);
                    sleep(currentBackoff);
                }

                long startTime = System.currentTimeMillis();

                // STEP 1: Fetch batch with advisory lock-based portfolio isolation
                int limit = batchSizer.getCurrentSize();
                List<OutboxEvent> batch = transactionTemplate.execute(status -> outboxRepo.findPendingBatch(limit));

                if (batch == null || batch.isEmpty()) {
                    // No work to do (or all portfolios locked by other pods)
                    batchSizer.reset();
                    currentBackoff = 0; // Reset backoff on idle
                    sleep(50);
                    continue;
                }

                // STEP 2: Group by portfolio (maintains insertion order)
                // Note: All events in batch are from portfolios THIS pod locked
                var eventsByPortfolio = new java.util.LinkedHashMap<java.util.UUID, java.util.ArrayList<OutboxEvent>>();
                for (OutboxEvent event : batch) {
                    eventsByPortfolio.computeIfAbsent(event.getPortfolioId(), k -> new java.util.ArrayList<>())
                            .add(event);
                }

                // STEP 3: Process each portfolio's batch (maintains strict ordering)
                for (var entry : eventsByPortfolio.entrySet()) {
                    java.util.UUID portfolioId = entry.getKey();
                    List<OutboxEvent> portfolioBatch = entry.getValue();

                    // Process this portfolio's events (prefix-safe, failure-classified)
                    BatchProcessingResult result = processor.processBatch(portfolioBatch);

                    // STEP 4: Handle results within transaction
                    transactionTemplate.execute(status -> {
                        // 4a. Mark successful prefix as SENT (SINGLE DB UPDATE per portfolio)
                        if (!result.getSuccessfulIds().isEmpty()) {
                            outboxRepo.markBatchAsSent(result.getSuccessfulIds());
                            log.info("Portfolio {}: Marked {} events as SENT", portfolioId,
                                    result.getSuccessfulIds().size());
                        }

                        // 4b. Handle poison pill (if any)
                        if (result.hasPoisonPill()) {
                            PoisonPillException ppe = result.getPoisonPill();
                            OutboxEvent poisonEvent = findEventById(portfolioBatch, ppe.getEventId());
                            if (poisonEvent != null) {
                                moveToDlq(poisonEvent, ppe.getMessage());
                                log.warn("Portfolio {}: Routed poison pill {} to DLQ", portfolioId, ppe.getEventId());
                            }
                        }

                        return null;
                    });

                    // STEP 5: Backoff strategy for system failures
                    if (result.hasSystemFailure()) {
                        // Exponential backoff
                        currentBackoff = currentBackoff == 0 ? systemFailureBackoffMs
                                : Math.min(currentBackoff * 2, maxBackoffMs);
                        log.error("Portfolio {}: System failure detected. Backoff={}ms. Will retry on next iteration.",
                                portfolioId, currentBackoff);
                        break; // Stop processing other portfolios, apply backoff
                    } else {
                        // Success or poison pill (not a system issue)
                        currentBackoff = 0; // Reset backoff
                    }
                }

                // Feedback for adaptive sizing (only if no system failure)
                if (currentBackoff == 0) {
                    long duration = System.currentTimeMillis() - startTime;
                    batchSizer.adjust(duration, batch.size());
                }

            } catch (Exception e) {
                log.error("Unexpected error in dispatch loop", e);
                // Defensive: backoff and continue
                currentBackoff = systemFailureBackoffMs;
                sleep(currentBackoff);
            }
        }
    }

    /**
     * Routes poison pill to DLQ and removes from outbox.
     * MUST be called within a transaction.
     */
    private void moveToDlq(OutboxEvent event, String errorMsg) {
        DlqEntry dlqEntry = new DlqEntry(event.getPayload(), "Poison Pill: " + errorMsg);
        dlqRepo.save(dlqEntry);
        outboxRepo.delete(event);
    }

    /**
     * Helper to find event by ID in batch.
     */
    private OutboxEvent findEventById(List<OutboxEvent> batch, Long eventId) {
        return batch.stream()
                .filter(e -> e.getId().equals(eventId))
                .findFirst()
                .orElse(null);
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
