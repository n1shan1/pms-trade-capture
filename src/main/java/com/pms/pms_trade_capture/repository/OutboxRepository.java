package com.pms.pms_trade_capture.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.pms.pms_trade_capture.domain.OutboxEvent;

@Repository
public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * CRITICAL: Fetches pending events with ADVISORY LOCK-based portfolio
     * isolation.
     * 
     * KEY ARCHITECTURAL DECISION:
     * We lock the PORTFOLIO (logical aggregate), not individual rows.
     * 
     * HOW IT WORKS:
     * 1. pg_try_advisory_xact_lock() attempts to acquire a transaction-scoped lock
     * on the portfolio ID
     * 2. If lock acquired → row is visible in result set
     * 3. If lock denied (another pod owns this portfolio) → row is filtered out
     * 4. Multiple portfolios can be processed in parallel (P1 on Pod A, P2 on Pod
     * B)
     * 5. Lock auto-released on commit/rollback (no cleanup needed)
     * 
     * WHY NOT SKIP LOCKED?
     * - Row-level locks allow "leapfrogging" (Pod B locks trade #101 while Pod A
     * has #100)
     * - This VIOLATES strict ordering guarantees
     * - Silent correctness failure: no exception, only wrong ordering in Kafka
     * 
     * WHY ADVISORY LOCKS?
     * - Locks the CONCEPT of "Portfolio P1" (not a specific row)
     * - One portfolio → one pod → one Kafka producer → guaranteed ordering
     * - Prevents the leapfrog race condition by construction
     * - PostgreSQL-native, deadlock-free (single lock per TX)
     * 
     * ORDERING GUARANTEE:
     * "Can Trade #101 ever overtake Trade #100 for the same portfolio?"
     * ANSWER: NO. Impossible. If Pod A owns portfolio P1, Pod B gets ZERO P1 rows.
     * 
     * @param limit Maximum number of events to fetch (for throughput tuning)
     * @return Ordered list of events from portfolios this pod successfully locked
     */
    @Query(value = """
            SELECT * FROM outbox_event
            WHERE status = 'PENDING'
            AND pg_try_advisory_xact_lock(hashtext(portfolio_id::text))
            ORDER BY created_at ASC, id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<OutboxEvent> findPendingBatch(@Param("limit") int limit);

    /**
     * Single-event update (kept for backward compatibility, but NOT used in batch
     * flow).
     */
    @Modifying
    @Transactional
    @Query(value = "UPDATE outbox_event SET status = 'SENT' WHERE id = :id", nativeQuery = true)
    void markSent(Long id);

    /**
     * CRITICAL: Bulk update for successful prefix.
     * This is the ONLY DB write per batch (except poison pills).
     * Must be called within a transaction boundary controlled by dispatcher.
     */
    @Modifying
    @Query(value = "UPDATE outbox_event SET status = 'SENT', sent_at = CURRENT_TIMESTAMP WHERE id IN (:ids)", nativeQuery = true)
    void markBatchAsSent(@Param("ids") List<Long> ids);

    /**
     * DEPRECATED: This method encourages N+1 anti-pattern. Do not use in
     * production.
     */
    @Deprecated
    @Modifying
    @Transactional
    @Query(value = "UPDATE outbox_event SET attempts = attempts + 1 WHERE id = :id", nativeQuery = true)
    void incrementAttempts(Long id);
}
