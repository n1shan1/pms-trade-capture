package com.pms.pms_trade_capture.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Immutable audit record of trade events ingested from RabbitMQ Streams.
 *
 * This table serves as the "safe store" - a permanent, append-only record
 * of all trade events received by the system. It enables:
 * - Audit trail and compliance requirements
 * - Data recovery and replay scenarios
 * - Historical analysis and reporting
 */
@Entity
@Table(name = "safe_store_trade")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SafeStoreTrade {
    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt = Instant.now();

    @Column(name = "portfolio_id", nullable = false)
    private UUID portfolioId;

    @Column(name = "trade_id", nullable = false, unique = true)
    private UUID tradeId;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false)
    private String side;

    @Column(name = "price_per_stock", nullable = false)
    private double pricePerStock;

    @Column(nullable = false)
    private long quantity;

    @Column(name = "event_timestamp", nullable = false)
    private LocalDateTime eventTimestamp;

    @Column(name = "timestamp", nullable = false)
    private LocalDateTime timestamp;

    /**
     * Constructor for creating new trade records (auto-generates ID)
     */
    public SafeStoreTrade(UUID portfolioId, UUID tradeId, String symbol, String side,
                          double pricePerStock, long quantity, LocalDateTime eventTimestamp) {
        this.portfolioId = portfolioId;
        this.tradeId = tradeId;
        this.symbol = symbol;
        this.side = side;
        this.pricePerStock = pricePerStock;
        this.quantity = quantity;
        this.eventTimestamp = eventTimestamp;
        this.timestamp = eventTimestamp;
    }
}
