package com.pms.pms_trade_capture.dto;

import java.time.Instant;
import java.util.UUID;

public class TradeEventDto {
    public UUID portfolioId;
    public UUID tradeId;
    public String symbol;
    public String side;
    public double pricePerStock;
    public long quantity;
    public Instant timestamp;

    public UUID getPortfolioId() {
        return portfolioId;
    }

    public String getTradeId() {
        return tradeId != null ? tradeId.toString() : null;
    }
}
