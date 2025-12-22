package com.pms.pms_trade_capture.exception;

/**
 * Marker exception for transient system failures.
 * These failures should trigger:
 * - Immediate batch termination (preserve ordering)
 * - Exponential backoff retry
 * - Circuit breaker patterns
 * 
 * Examples:
 * - Kafka broker unavailable
 * - Network partition
 * - Timeout errors
 * - Metadata fetch failures
 * 
 * These should NEVER result in DLQ routing.
 */
public class SystemFailureException extends Exception {
    
    public SystemFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}
