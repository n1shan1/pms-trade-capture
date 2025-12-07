package com.pms.pms_trade_capture.service;

import com.pms.pms_trade_capture.domain.DlqEntry;
import com.pms.pms_trade_capture.domain.OutboxEvent;
import com.pms.pms_trade_capture.domain.SafeStoreTrade;
import com.pms.pms_trade_capture.dto.TradeEventDto;

public interface CrossCuttingService {

    void recordIngestionSuccess(TradeEventDto tradeEvent,
                                SafeStoreTrade safeStoreTrade,
                                OutboxEvent outboxEvent);

    void recordIngestionFailure(TradeEventDto tradeEvent,
                                DlqEntry dlqEntry,
                                Exception ex);
}
