package com.pms.pms_trade_capture.dto;

import com.google.protobuf.Timestamp;
import com.pms.pms_trade_capture.proto.TradeEventProto;
import java.time.Instant;

public class TradeProtoMapper {
    public static TradeEventProto toProto(TradeEventDto dto) {
        Instant inst = dto.timestamp;
        Timestamp ts = Timestamp.newBuilder().setSeconds(inst.getEpochSecond()).setNanos(inst.getNano()).build();

        return TradeEventProto.newBuilder()
                .setPortfolioId(dto.portfolioId.toString())
                .setTradeId(dto.tradeId.toString())
                .setSymbol(dto.symbol)
                .setSide(dto.side)
                .setPricePerStock(dto.pricePerStock)
                .setQuantity(dto.quantity)
                .setTimestamp(ts)
                .build();
    }
}
