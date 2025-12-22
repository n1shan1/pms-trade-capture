package com.pms.pms_trade_capture.outbox;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.pms.pms_trade_capture.dto.BatchProcessingResult;
import com.pms.pms_trade_capture.exception.PoisonPillException;
import com.pms.pms_trade_capture.exception.SystemFailureException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.google.protobuf.InvalidProtocolBufferException;
import com.pms.pms_trade_capture.domain.OutboxEvent;
import com.pms.trade_capture.proto.TradeEventProto;

/**
 * PRODUCTION-GRADE Outbox Event Processor with strict failure classification.
 * 
 * KEY GUARANTEES:
 * 1. Processes events in strict order (no overtaking)
 * 2. Stops immediately on system failures (Kafka down, network errors)
 * 3. Skips poison pills and routes to DLQ (serialization errors, invalid data)
 * 4. Returns only the continuous successful prefix for bulk DB update
 */
@Component
public class OutboxEventProcessor {
    private static final Logger log = LoggerFactory.getLogger(OutboxEventProcessor.class);

    private final KafkaTemplate<String, TradeEventProto> kafkaTemplate;

    @Value("${app.outbox.trade-topic}")
    private String tradeTopic;

    @Value("${app.outbox.kafka-send-timeout-ms:5000}")
    private long kafkaSendTimeoutMs;

    public OutboxEventProcessor(KafkaTemplate<String, TradeEventProto> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * CRITICAL: Processes batch with prefix-safe semantics.
     * 
     * Behavior:
     * - Send trades one-by-one in order (blocking)
     * - On POISON PILL: skip it, record for DLQ, continue batch
     * - On SYSTEM FAILURE: STOP immediately, return successful prefix
     * - Collect successful IDs for bulk DB update
     * 
     * @param events Ordered list of events to process
     * @return BatchProcessingResult containing successful prefix and any failures
     */
    public BatchProcessingResult processBatch(List<OutboxEvent> events) {
        List<Long> successfulIds = new ArrayList<>();

        for (OutboxEvent event : events) {
            try {
                sendToKafka(event);
                successfulIds.add(event.getId());

            } catch (PoisonPillException ppe) {
                // POISON PILL: This event is permanently broken
                // Return what succeeded so far + this poison pill for DLQ routing
                log.error("Poison pill detected: Event ID={}, Error={}", event.getId(), ppe.getMessage());
                return BatchProcessingResult.withPoisonPill(successfulIds, ppe);

            } catch (SystemFailureException sfe) {
                // SYSTEM FAILURE: Kafka is down or network issues
                // STOP batch immediately, do NOT update DB for failed events
                // Return successful prefix only
                log.error("System failure detected: {}. Stopping batch to preserve ordering.", sfe.getMessage());
                return BatchProcessingResult.systemFailure(successfulIds);
            }
        }

        // All events sent successfully
        return BatchProcessingResult.success(successfulIds);
    }

    /**
     * Sends a single event to Kafka with timeout and failure classification.
     * 
     * @throws PoisonPillException    if the failure is permanent (bad data,
     *                                serialization error)
     * @throws SystemFailureException if the failure is transient (Kafka down,
     *                                timeout)
     */
    private void sendToKafka(OutboxEvent event) throws PoisonPillException, SystemFailureException {
        try {
            // 1. Deserialize protobuf (can throw InvalidProtocolBufferException = poison
            // pill)
            TradeEventProto proto = TradeEventProto.parseFrom(event.getPayload());

            String key = event.getPortfolioId().toString();

            // 2. Blocking send with timeout
            kafkaTemplate.send(tradeTopic, key, proto)
                    .get(kafkaSendTimeoutMs, TimeUnit.MILLISECONDS);

            log.debug("Sent event {} to Kafka topic {}", event.getId(), tradeTopic);

        } catch (InvalidProtocolBufferException e) {
            // Corrupt payload in DB = POISON PILL
            throw new PoisonPillException(event.getId(), "Invalid protobuf payload", e);

        } catch (ExecutionException e) {
            classifyAndThrow(event.getId(), e.getCause());

        } catch (TimeoutException e) {
            // Kafka send timeout = SYSTEM FAILURE (broker slow/down)
            throw new SystemFailureException("Kafka send timeout after " + kafkaSendTimeoutMs + "ms", e);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SystemFailureException("Thread interrupted during Kafka send", e);
        }
    }

    /**
     * CRITICAL: Classifies exceptions into poison pills vs system failures.
     * 
     * POISON PILLS (permanent, route to DLQ):
     * - SerializationException (bad protobuf, schema mismatch)
     * - RecordTooLargeException (event exceeds broker limits)
     * - IllegalArgumentException (null key/value, validation errors)
     * 
     * SYSTEM FAILURES (transient, retry with backoff):
     * - Network errors (UnknownHostException, ConnectException)
     * - Broker unavailable (NotLeaderForPartitionException,
     * BrokerNotAvailableException)
     * - Timeout errors (org.apache.kafka.common.errors.TimeoutException)
     * - Metadata errors (InvalidTopicException during broker issues)
     * - Any other unexpected exception (fail-safe: treat as system failure)
     */
    private void classifyAndThrow(Long eventId, Throwable cause)
            throws PoisonPillException, SystemFailureException {

        // Unwrap nested causes (e.g., ExecutionException -> actual error)
        Throwable rootCause = cause;
        while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
            rootCause = rootCause.getCause();
        }

        String errorMsg = rootCause.getClass().getSimpleName() + ": " + rootCause.getMessage();

        // ===== POISON PILL DETECTION =====

        // Serialization failures (bad protobuf, schema incompatibility)
        if (rootCause instanceof SerializationException) {
            throw new PoisonPillException(eventId, "Kafka serialization failed: " + errorMsg, cause);
        }

        // Message size exceeds broker limits
        if (rootCause instanceof RecordTooLargeException) {
            throw new PoisonPillException(eventId, "Record too large for Kafka: " + errorMsg, cause);
        }

        // Validation errors (null key, null value, etc.)
        if (rootCause instanceof IllegalArgumentException || rootCause instanceof NullPointerException) {
            throw new PoisonPillException(eventId, "Invalid event data: " + errorMsg, cause);
        }

        // ===== SYSTEM FAILURE DETECTION =====
        // Everything else is assumed to be a transient failure

        throw new SystemFailureException("Kafka system failure: " + errorMsg, cause);
    }
}
