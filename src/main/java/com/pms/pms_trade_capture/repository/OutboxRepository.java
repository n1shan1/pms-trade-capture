package com.pms.pms_trade_capture.repository;

import java.util.List;
import java.util.UUID;

import com.pms.pms_trade_capture.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;



@Repository
public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    @Query(value = "SELECT * FROM outbox_event WHERE status = 'PENDING' ORDER BY created_at LIMIT :limit", nativeQuery = true)
    List<OutboxEvent> findPending(int limit);

    @Modifying
    @Transactional
    @Query(value = "UPDATE outbox_event SET status = 'SENT' WHERE id = :id", nativeQuery = true)
    void markSent(UUID id);

    @Modifying
    @Transactional
    @Query(value = "UPDATE outbox_event SET attempts = attempts + 1 WHERE id = :id", nativeQuery = true)
    void incrementAttempts(UUID id);
}
