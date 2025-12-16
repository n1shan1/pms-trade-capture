package com.pms.pms_trade_capture.dto;

import com.pms.pms_trade_capture.domain.OutboxEvent;
import com.pms.pms_trade_capture.domain.PendingStreamMessage;
import com.pms.pms_trade_capture.domain.SafeStoreTrade;
import com.pms.trade_capture.proto.TradeEventProto;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
public class TradeEventMapper {
    /**
     * Convert Protobuf Message -> Audit Log Entity
     */
    public static SafeStoreTrade protoToSafeStoreTrade(TradeEventProto proto, byte[] rawMessage) {
        return new SafeStoreTrade(
                UUID.fromString(proto.getPortfolioId()),
                UUID.fromString(proto.getTradeId()),
                proto.getSymbol(),
                proto.getSide(),
                proto.getPricePerStock(),
                proto.getQuantity(),
                protoTimestampToLocalDateTime(proto.getTimestamp()),
                rawMessage
        );
    }

    /**
     * Convert Protobuf Message -> Outbox Entity
     * This preserves the EXACT raw bytes of the incoming message for the outbox.
     */
    public static OutboxEvent protoToOutboxEvent(TradeEventProto proto) {
        return new OutboxEvent(
                UUID.fromString(proto.getPortfolioId()),
                UUID.fromString(proto.getTradeId()),
                proto.toByteArray() // Store exact source bytes
        );
    }

    /**
     * Convert Protobuf Message -> Internal DTO (for metrics/logging)
     */
    public static TradeEventDto protoToDto(TradeEventProto proto) {
        return new TradeEventDto(
                UUID.fromString(proto.getPortfolioId()),
                UUID.fromString(proto.getTradeId()),
                proto.getSymbol(),
                proto.getSide(),
                proto.getPricePerStock(),
                proto.getQuantity(),
                Instant.ofEpochSecond(proto.getTimestamp().getSeconds(), proto.getTimestamp().getNanos())
        );
    }

    /**
     * Convert DTO to OutboxEvent entity
     */
    public static OutboxEvent toOutboxEvent(TradeEventDto dto, byte[] payload) {
        return new OutboxEvent(dto.portfolioId, dto.tradeId, payload);
    }

    // Helper: Google Timestamp -> Java LocalDateTime (UTC)
    private static LocalDateTime protoTimestampToLocalDateTime(com.google.protobuf.Timestamp ts) {
        if (ts == null) return LocalDateTime.now();
        return LocalDateTime.ofInstant(
                Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos()),
                ZoneOffset.UTC
        );
    }

    /**
     * Helper to map a PendingStreamMessage directly to a SafeStoreTrade entity.
     * Used by the BatchingIngestService.
     */
    public static SafeStoreTrade pendingMessageToSafeStoreTrade(PendingStreamMessage message) {
        if (!message.isValid()) {
            throw new IllegalArgumentException("Cannot map invalid pending message to trade entity");
        }
        return protoToSafeStoreTrade(message.getTrade(), message.getRawMessageBytes());
    }
}
