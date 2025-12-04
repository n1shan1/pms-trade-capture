package com.pms.pms_trade_capture.stream;

import com.pms.pms_trade_capture.proto.TradeEventProto;
import lombok.Getter;

/**
 * Represents a pending message from RabbitMQ Stream that has been received
 * but not yet persisted to the database or had its offset committed.
 *
 * This class pairs a trade event with its stream offset to enable proper
 * crash recovery semantics:
 * - Offset is committed ONLY after successful DB persistence
 * - If crash occurs before commit, stream will replay from last committed offset
 * - This guarantees at-least-once delivery without data loss
 */
@Getter
public class PendingStreamMessage {

    private final TradeEventProto trade;
    private final long offset;

    public PendingStreamMessage(TradeEventProto trade, long offset) {
        this.trade = trade;
        this.offset = offset;
    }

}
