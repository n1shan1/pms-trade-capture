package com.pms.pms_trade_capture.exception;

/**
 * Marker exception for permanent failures (poison pills).
 * These failures cannot be resolved by retrying and should be routed to DLQ immediately.
 * 
 * Examples:
 * - Protobuf deserialization errors
 * - Schema validation failures
 * - Invalid data format
 * 
 * System failures (Kafka unavailable, network errors, etc.) should NOT use this exception.
 */
public class PoisonPillException extends Exception {
    private final Long eventId;
    
    public PoisonPillException(Long eventId, String message, Throwable cause) {
        super(message, cause);
        this.eventId = eventId;
    }
    
    public Long getEventId() {
        return eventId;
    }
}
