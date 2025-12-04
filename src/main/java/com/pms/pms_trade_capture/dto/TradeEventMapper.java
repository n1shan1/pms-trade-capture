package com.pms.pms_trade_capture.dto;


import com.pms.pms_trade_capture.domain.OutboxEvent;
import com.pms.pms_trade_capture.domain.SafeStoreTrade;
import com.pms.pms_trade_capture.proto.TradeEventProto;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Mapper for converting between different trade event representations:
 * - TradeEventDto (internal DTO)
 * - TradeEventProto (Protobuf message)
 * - Domain entities (SafeStoreTrade, OutboxEvent)
 */
public class TradeEventMapper {
    /**
     * Convert DTO to SafeStoreTrade entity
     */
    public static SafeStoreTrade toSafeStoreTrade(TradeEventDto dto) {
        return new SafeStoreTrade(
                dto.portfolioId,
                dto.tradeId,
                dto.symbol,
                dto.side,
                dto.pricePerStock,
                dto.quantity,
                LocalDateTime.ofInstant(dto.timestamp, ZoneOffset.UTC)
        );
    }

    /**
     * Convert DTO to OutboxEvent entity
     */
    public static OutboxEvent toOutboxEvent(TradeEventDto dto, byte[] payload) {
        return new OutboxEvent(dto.portfolioId, dto.tradeId, payload);
    }

    /**
     * Convert Protobuf message to SafeStoreTrade entity
     */
    public static SafeStoreTrade protoToSafeStoreTrade(TradeEventProto proto) {
        UUID portfolioId = UUID.fromString(proto.getPortfolioId());
        UUID tradeId = UUID.fromString(proto.getTradeId());

        Instant timestamp = Instant.ofEpochSecond(
                proto.getTimestamp().getSeconds(),
                proto.getTimestamp().getNanos()
        );

        return new SafeStoreTrade(
                portfolioId,
                tradeId,
                proto.getSymbol(),
                proto.getSide(),
                proto.getPricePerStock(),
                proto.getQuantity(),
                LocalDateTime.ofInstant(timestamp, ZoneOffset.UTC)
        );
    }

    /**
     * Convert Protobuf message to OutboxEvent entity
     */
    public static OutboxEvent protoToOutboxEvent(TradeEventProto proto, byte[] payload) {
        UUID portfolioId = UUID.fromString(proto.getPortfolioId());
        UUID tradeId = UUID.fromString(proto.getTradeId());

        return new OutboxEvent(portfolioId, tradeId, payload);
    }

    /**
     * Convert Protobuf message to DTO
     */
    public static TradeEventDto protoToDto(TradeEventProto proto) {
        TradeEventDto dto = new TradeEventDto();
        dto.portfolioId = UUID.fromString(proto.getPortfolioId());
        dto.tradeId = UUID.fromString(proto.getTradeId());
        dto.symbol = proto.getSymbol();
        dto.side = proto.getSide();
        dto.pricePerStock = proto.getPricePerStock();
        dto.quantity = proto.getQuantity();
        dto.timestamp = Instant.ofEpochSecond(
                proto.getTimestamp().getSeconds(),
                proto.getTimestamp().getNanos()
        );
        return dto;
    }
}
