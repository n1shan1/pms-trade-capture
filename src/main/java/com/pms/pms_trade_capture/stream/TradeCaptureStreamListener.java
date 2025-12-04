package com.pms.pms_trade_capture.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.google.protobuf.InvalidProtocolBufferException;
import com.pms.pms_trade_capture.config.RabbitStreamConfig;
import com.pms.pms_trade_capture.domain.DlqEntry;
import com.pms.pms_trade_capture.proto.TradeEventProto;
import com.pms.pms_trade_capture.repository.DlqRepository;
import com.pms.pms_trade_capture.service.BatchingIngestService;
import com.rabbitmq.stream.Consumer;
import com.rabbitmq.stream.Environment;
import com.rabbitmq.stream.OffsetSpecification;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * RabbitMQ Streams consumer for trade events using offset-based reliability.
 *
 * IMPORTANT: This is NOT an AMQP queue consumer. There are NO concepts of:
 * - deliveryTag
 * - Channel
 * - basicAck/basicNack
 * - multiple acknowledge flags
 *
 * Instead, this uses RabbitMQ Streams with:
 * - Stream offsets (long values, not deliveryTag)
 * - Explicit offset commits via consumer.store(offset)
 * - Crash recovery via offset replay
 *
 * Key differences from classic RabbitMQ:
 * - Streams are append-only logs (like Kafka topics)
 * - Multiple consumers can read independently
 * - Ordering is guaranteed per stream partition
 * - Messages are not removed after consumption
 * - Consumers track their own offset
 *
 * Architecture:
 * - Consumes Protobuf-serialized TradeEventProto messages
 * - Extracts stream offset from message context
 * - Creates PendingStreamMessage (trade + offset)
 * - Routes to BatchingIngestService for buffering
 * - Handles deserialization failures by writing to DLQ
 * - Named consumer allows resuming from last committed offset
 *
 * Crash Safety:
 * - Messages are buffered with offsets in BatchingIngestService
 * - Offsets are committed ONLY after successful DB persistence
 * - If crash occurs before offset commit:
 *   * Stream replays from last committed offset
 *   * DB idempotency handles duplicate trade_ids
 *   * No data is lost
 *
 * Ordering:
 * - Simulator routes all trades for a portfolio to same partition
 * - Stream guarantees FIFO per partition
 * - Single-threaded consumer preserves order
 * - BatchingIngestService maintains order within portfolio buffers
 * - Result: Strict per-portfolio ordering end-to-end
 */
@Component
public class TradeCaptureStreamListener {

    private static final Logger log = LoggerFactory.getLogger(TradeCaptureStreamListener.class);

    private final Environment streamEnvironment;
    private final RabbitStreamConfig streamConfig;
    private final BatchingIngestService batchingIngestService;
    private final DlqRepository dlqRepository;

    private Consumer consumer;

    public TradeCaptureStreamListener(Environment streamEnvironment,
                                      RabbitStreamConfig streamConfig,
                                      BatchingIngestService batchingIngestService,
                                      DlqRepository dlqRepository) {
        this.streamEnvironment = streamEnvironment;
        this.streamConfig = streamConfig;
        this.batchingIngestService = batchingIngestService;
        this.dlqRepository = dlqRepository;
    }

    @PostConstruct
    public void start() {
        String streamName = streamConfig.getStreamName();
        String consumerName = streamConfig.getConsumerName();

        log.info("Starting RabbitMQ Stream consumer: stream={}, consumer={}",
                streamName, consumerName);

        try {
            // Declare the stream if it doesn't exist
            streamEnvironment.streamCreator().stream(streamName).create();

            // Create single named consumer with offset tracking
            consumer = streamEnvironment.consumerBuilder()
                    .stream(streamName)
                    .name(consumerName) // Single named consumer
                    .offset(OffsetSpecification.next()) // Resume from last committed offset
                    .messageHandler((context, message) -> {
                        // Extract stream offset from message context
                        long offset = context.offset();
                        byte[] body = message.getBodyAsBinary();
                        handleMessage(body, offset);
                    })
                    .build();

            // Register consumer with batching service for offset commits
            batchingIngestService.setStreamConsumer(consumer);

            log.info("RabbitMQ Stream consumer started successfully");
        } catch (Exception e) {
            log.error("Failed to start RabbitMQ Stream consumer", e);
            throw new RuntimeException("Failed to start stream consumer", e);
        }
    }

    @PreDestroy
    public void stop() {
        log.info("Stopping RabbitMQ Stream consumer...");
        if (consumer != null) {
            try {
                consumer.close();
                log.info("RabbitMQ Stream consumer stopped");
            } catch (Exception e) {
                log.error("Error stopping consumer", e);
            }
        }
    }

    /**
     * Handle incoming message from stream with its offset.
     *
     * CRITICAL: The offset is NOT a deliveryTag. It's a stream offset (long).
     * Offset commit happens in BatchingIngestService AFTER DB persistence.
     *
     * @param messageBody Raw bytes from stream
     * @param offset Stream offset for this message
     */
    private void handleMessage(byte[] messageBody, long offset) {
        try {
            // Parse Protobuf message
            TradeEventProto proto = TradeEventProto.parseFrom(messageBody);

            // Validate required fields
            if (proto.getPortfolioId().isEmpty() || proto.getTradeId().isEmpty()) {
                log.warn("Received trade event with missing required fields at offset {}", offset);
                saveToDlq(messageBody, "Missing required fields (portfolioId or tradeId)");
                // Still need to commit offset to avoid infinite replay
                if (consumer != null) {
                    consumer.store(offset);
                }
                return;
            }

            // Create pending message with trade and offset
            PendingStreamMessage pendingMessage = new PendingStreamMessage(proto, offset);

            // Add to batching service - offset will be committed after DB persistence
            batchingIngestService.addMessage(pendingMessage);

            log.trace("Accepted trade event at offset {}: portfolio={}, trade={}",
                    offset, proto.getPortfolioId(), proto.getTradeId());

        } catch (InvalidProtocolBufferException e) {
            log.error("Failed to parse Protobuf message at offset {}", offset, e);
            saveToDlq(messageBody, "Invalid Protobuf: " + e.getMessage());
            // Commit offset to avoid infinite replay of bad message
            if (consumer != null) {
                consumer.store(offset);
            }
        } catch (Exception e) {
            log.error("Unexpected error processing message at offset {}", offset, e);
            saveToDlq(messageBody, "Processing error: " + e.getMessage());
            // DO NOT commit offset here - let it retry on replay
        }
    }

    /**
     * Save unparseable or invalid messages to Dead Letter Queue.
     *
     * @param messageBody Original message bytes
     * @param errorDetail Error description
     */
    private void saveToDlq(byte[] messageBody, String errorDetail) {
        try {
            String rawMessage = new String(messageBody);
            DlqEntry entry = new DlqEntry(rawMessage, errorDetail);
            dlqRepository.save(entry);
            log.debug("Saved message to DLQ: {}", errorDetail);
        } catch (Exception e) {
            log.error("Failed to save to DLQ", e);
            // Last resort: log the message
            log.error("LOST MESSAGE (DLQ save failed): {}", new String(messageBody));
        }
    }
}
