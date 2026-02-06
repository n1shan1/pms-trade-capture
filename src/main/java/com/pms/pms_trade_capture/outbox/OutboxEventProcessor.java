package com.pms.pms_trade_capture.outbox;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.google.protobuf.InvalidProtocolBufferException;
import com.pms.pms_trade_capture.domain.OutboxEvent;
import com.pms.pms_trade_capture.dto.BatchProcessingResult;
import com.pms.pms_trade_capture.exception.PoisonPillException;
import com.pms.pms_trade_capture.exception.SystemFailureException;
import com.pms.trade_capture.proto.TradeEventProto;
import com.pms.rttm.client.clients.RttmClient;
import com.pms.rttm.client.dto.TradeEventPayload;
import com.pms.rttm.client.dto.DlqEventPayload;
import com.pms.rttm.client.enums.EventType;
import com.pms.rttm.client.enums.EventStage;

/**
 * Processes outbox events by sending them to Kafka with proper failure classification.
 * Distinguishes between poison pills (permanent failures) and system failures (transient).
 */
@Component
public class OutboxEventProcessor {
    private static final Logger log = LoggerFactory.getLogger(OutboxEventProcessor.class);

    private final KafkaTemplate<String, TradeEventProto> kafkaTemplate;
    private final RttmClient rttmClient;

    @Value("${app.outbox.trade-topic}")
    private String tradeTopic;

    @Value("${app.outbox.kafka-send-timeout-ms:5000}")
    private long kafkaSendTimeoutMs;

    @Value("${spring.application.name}")
    private String serviceName;

    public OutboxEventProcessor(KafkaTemplate<String, TradeEventProto> kafkaTemplate, RttmClient rttmClient) {
        this.kafkaTemplate = kafkaTemplate;
        this.rttmClient = rttmClient;
    }

    /**
     * Processes a batch of events, sending each to Kafka and classifying failures.
     * Stops on system failures but continues past poison pills, returning only the successful prefix.
     *
     * @param events the ordered list of events to process
     * @return result containing successful event IDs and any failures encountered
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
     * Sends a single event to Kafka with timeout handling and failure classification.
     *
     * @throws PoisonPillException if the event data is permanently corrupted
     * @throws SystemFailureException if Kafka is unavailable or network issues occur
     */
    private void sendToKafka(OutboxEvent event) throws PoisonPillException, SystemFailureException {
        try {
            // 1. Deserialize protobuf (can throw InvalidProtocolBufferException = poison pill)
            TradeEventProto proto = TradeEventProto.parseFrom(event.getPayload());

            String key = event.getPortfolioId().toString();

            // 2. Blocking send with timeout
            var sendResult = kafkaTemplate.send(tradeTopic, key, proto)
                    .get(kafkaSendTimeoutMs, TimeUnit.MILLISECONDS);

            var metadata = sendResult.getRecordMetadata();
            int partition = metadata.partition();
            long offset = metadata.offset();

            log.debug("Sent event {} to Kafka topic {}", event.getId(), tradeTopic);

            // Send trade completion event to RTTM
            sendTradeCompletionEvent(proto, partition, offset);

        } catch (InvalidProtocolBufferException e) {
            // Corrupt payload in DB = POISON PILL
            sendDlqEventToRttm(event, "Invalid protobuf payload: " + e.getMessage());
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

    /**
     * Send trade completion event to RTTM
     */
    private void sendTradeCompletionEvent(TradeEventProto proto, int partition, long offset) {
        try {
            TradeEventPayload event = TradeEventPayload.builder()
                    .tradeId(proto.getTradeId())
                    .serviceName(serviceName)
                    .eventType(EventType.TRADE_DISPATCHED)
                    .eventStage(EventStage.DISPATCHED)
                    .eventStatus("OK")
                    .targetQueue(tradeTopic)
                    .message("Trade dispatched to downstream service")
                    .topicName(tradeTopic)
                    .offsetValue(offset)
                    .partitionId(partition)
                    .build();

            rttmClient.sendTradeEvent(event);
            log.debug("Sent trade completion event to RTTM for trade {}", proto.getTradeId());
        } catch (Exception ex) {
            log.warn("Failed to send trade completion event to RTTM: {}", ex.getMessage());
        }
    }

    /**
     * Send DLQ event to RTTM when outbox processing fails
     */
    private void sendDlqEventToRttm(OutboxEvent event, String errorReason) {
        try {
            DlqEventPayload dlqEvent = DlqEventPayload.builder()
                    .tradeId(event.getTradeId().toString())
                    .serviceName(serviceName)
                    .topicName("trade_capture_outbox_dlq")
                    .originalTopic(tradeTopic)
                    .reason(errorReason)
                    .eventStage(EventStage.DISPATCHED)
                    .build();

            rttmClient.sendDlqEvent(dlqEvent);
            log.debug("Sent DLQ event to RTTM for trade {}", event.getTradeId());
        } catch (Exception ex) {
            log.warn("Failed to send DLQ event to RTTM: {}", ex.getMessage());
        }
    }
}
