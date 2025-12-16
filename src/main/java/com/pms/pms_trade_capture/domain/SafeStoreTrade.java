package com.pms.pms_trade_capture.domain;

import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "safe_store_trade")
@Data
@NoArgsConstructor
public class SafeStoreTrade {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "safe_store_seq")
    @SequenceGenerator(name = "safe_store_seq", sequenceName = "safe_store_trade_seq", allocationSize = 50)
    private Long id;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt = LocalDateTime.now();

    @Column(name = "portfolio_id", nullable = false)
    private UUID portfolioId;

    // Business Key (Ensures Idempotency)
    // @Column(name = "trade_id", nullable = false, unique = true)
    @Column(name = "trade_id", nullable = false)
    private UUID tradeId;

    // NEW: Store raw data for audit/replay of invalid rows
    @Column(name = "raw_payload")
    @JdbcTypeCode(SqlTypes.VARBINARY)
    private byte[] rawPayload;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false)
    private String side;

    @Column(name = "price_per_stock", nullable = false)
    private double pricePerStock;

    @Column(nullable = false)
    private long quantity;

    // Business timestamp (from the exchange/simulator)
    @Column(name = "event_timestamp", nullable = false)
    private LocalDateTime eventTimestamp;

    @Column(name = "is_valid", nullable = false)
    private boolean valid;

    // Sentinel UUID for invalid data: 00000000-0000-0000-0000-000000000000
    public static final UUID INVALID_UUID = new UUID(0L, 0L);

    // Custom constructor for mapper usage
    public SafeStoreTrade(UUID portfolioId, UUID tradeId, String symbol, String side,
            double pricePerStock, long quantity, LocalDateTime eventTimestamp, byte[] rawPayload) {
        this.portfolioId = portfolioId;
        this.tradeId = tradeId;
        this.symbol = symbol;
        this.side = side;
        this.pricePerStock = pricePerStock;
        this.quantity = quantity;
        this.eventTimestamp = eventTimestamp;
        this.rawPayload = rawPayload;
        this.valid = true;
        this.receivedAt = LocalDateTime.now();
    }

    public static SafeStoreTrade createInvalid(byte[] rawPayload) {
        SafeStoreTrade trade = new SafeStoreTrade();
        trade.setPortfolioId(INVALID_UUID);
        trade.setTradeId(UUID.randomUUID()); // Generate random to avoid Unique Constraint crash on bad data
        trade.setSymbol("INVALID_DATA");
        trade.setSide("UNKNOWN");
        trade.setPricePerStock(-1.0);
        trade.setQuantity(0);
        trade.setEventTimestamp(LocalDateTime.now());
        trade.setRawPayload(rawPayload);
        trade.setValid(false);
        trade.setReceivedAt(LocalDateTime.now());
        return trade;
    }

}
