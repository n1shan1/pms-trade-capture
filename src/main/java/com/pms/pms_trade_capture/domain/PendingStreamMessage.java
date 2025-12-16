package com.pms.pms_trade_capture.domain;

import com.pms.trade_capture.proto.TradeEventProto;

import lombok.Getter;
import lombok.Setter;

/**
 * Represents a pending message from RabbitMQ Stream that has been received
 * but not yet persisted to the database or had its offset committed.
 * * This class pairs a trade event with its stream offset to enable proper
 * crash recovery semantics:
 * - Offset is committed ONLY after successful DB persistence
 * - If crash occurs before commit, stream will replay from last committed
 * offset
 * - This guarantees at-least-once delivery without data loss
 */
@Getter
@Setter
public class PendingStreamMessage {
    private final TradeEventProto trade;

    private final byte[] rawMessageBytes;

    private final long offset;

    private final String parseError;

    public PendingStreamMessage(TradeEventProto trade, byte[] rawMessageBytes, long offset) {
        this.trade = trade;
        this.rawMessageBytes = rawMessageBytes;
        this.offset = offset;
        this.parseError = null;
    }

    public PendingStreamMessage(byte[] rawMessageBytes, long offset, String parseError) {
        this.trade = null;
        this.rawMessageBytes = rawMessageBytes;
        this.offset = offset;
        this.parseError = parseError;
    }

    public boolean isValid() {
        return trade != null && parseError == null;
    }
}
