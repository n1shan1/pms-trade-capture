package com.pms.pms_trade_capture.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbox pattern implementation for reliable event publishing.
 *
 * Events are first persisted in the database atomically with business data,
 * then asynchronously published to Kafka by the OutboxDispatcher.
 *
 * This pattern guarantees:
 * - At-least-once delivery (events may be retried on failure)
 * - No lost messages (even if Kafka is temporarily unavailable)
 * - Transactional consistency between database and message bus
 */
@Entity
@Table(name = "outbox_event")
@Data
@NoArgsConstructor
public class OutboxEvent {
    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "portfolio_id", nullable = false)
    private UUID portfolioId;

    @Column(name = "trade_id", nullable = false)
    private UUID tradeId;

    @Column(name = "payload", nullable = false, columnDefinition = "bytea")
    private byte[] payload; // Protobuf serialized TradeEventProto bytes

    @Column(name = "status", nullable = false)
    private String status = "PENDING"; // PENDING | SENT

    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    /**
     * Constructor for creating new outbox events (auto-generates ID)
     */
    public OutboxEvent(UUID portfolioId, UUID tradeId, byte[] payload) {
        this.portfolioId = portfolioId;
        this.tradeId = tradeId;
        this.payload = payload;
    }
}
