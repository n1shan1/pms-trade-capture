package com.pms.pms_trade_capture.domain;

import com.pms.trade_capture.proto.TradeEventProto;
import com.rabbitmq.stream.MessageHandler;

import lombok.Getter;

/**
 * Represents a pending message from RabbitMQ Stream that has been received
 * but not yet persisted to the database or had its offset committed.
 * 
 * This class pairs a trade event with its stream offset to enable proper
 * crash recovery semantics:
 * - Offset is committed ONLY after successful DB persistence
 * - If crash occurs before commit, stream will replay from last committed
 * offset
 * - This guarantees at-least-once delivery without data loss
 * 
 * HFT Requirements:
 * - Stores MessageHandler.Context for manual offset management
 * - Immutable design for thread-safety across consumer/flush threads
 * - Raw bytes preserved for DLQ/audit trail
 */
@Getter
public class PendingStreamMessage {
    private final TradeEventProto trade;
    private final byte[] rawMessageBytes;
    private final long offset;
    private final String parseError;

    // CRITICAL: Context needed for manual offset commit
    private final MessageHandler.Context context;

    /**
     * Valid message constructor (parsed successfully)
     */
    public PendingStreamMessage(TradeEventProto trade, byte[] rawMessageBytes, long offset,
            MessageHandler.Context context) {
        this.trade = trade;
        this.rawMessageBytes = rawMessageBytes;
        this.offset = offset;
        this.parseError = null;
        this.context = context;
    }

    /**
     * Invalid message constructor (parse failure/poison pill)
     */
    public PendingStreamMessage(byte[] rawMessageBytes, long offset, String parseError,
            MessageHandler.Context context) {
        this.trade = null;
        this.rawMessageBytes = rawMessageBytes;
        this.offset = offset;
        this.parseError = parseError;
        this.context = context;
    }

    public boolean isValid() {
        return trade != null && parseError == null;
    }
}
