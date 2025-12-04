package com.pms.pms_trade_capture.outbox;

import com.pms.pms_trade_capture.domain.OutboxEvent;
import com.pms.pms_trade_capture.repository.OutboxRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.pms.pms_trade_capture.proto.TradeEventProto;
import org.springframework.kafka.core.KafkaTemplate;
import java.util.List;


/**
 * Outbox pattern dispatcher for publishing events to Kafka.
 *
 * Continuously polls the outbox_event table for PENDING records and
 * publishes them to Kafka with the Confluent Schema Registry.
 *
 * Key features:
 * - At-least-once delivery (retries on failure)
 * - Ordering preserved per portfolio (via Kafka partition key)
 * - Automatic schema registration via KafkaProtobufSerializer
 * - Transactional outbox pattern for reliability
 *
 * Configuration:
 * - app.kafka.trade-topic: Target Kafka topic name
 * - app.outbox.poll-interval-ms: Polling frequency
 * - app.outbox.batch-size: Max records to fetch per poll
 */
@Component
public class OutboxDispatcher {
    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxRepository outboxRepo;
    private final KafkaTemplate<String, TradeEventProto> kafkaTemplate;

    @Value("${app.kafka.trade-topic}")
    private String tradeTopic;

    @Value("${app.outbox.poll-interval-ms}")
    private long pollIntervalMs;

    @Value("${app.outbox.batch-size}")
    private int batchSize;

    private volatile boolean running = false;
    private Thread dispatcherThread;

    public OutboxDispatcher(OutboxRepository outboxRepo,
                            KafkaTemplate<String, TradeEventProto> kafkaTemplate) {
        this.outboxRepo = outboxRepo;
        this.kafkaTemplate = kafkaTemplate;
    }

    @PostConstruct
    public void start() {
        running = true;
        dispatcherThread = new Thread(this::runLoop, "outbox-dispatcher");
        dispatcherThread.setDaemon(false); // Keep JVM alive until graceful shutdown
        dispatcherThread.start();
        log.info("OutboxDispatcher started: topic={}, pollInterval={}ms, batchSize={}",
                tradeTopic, pollIntervalMs, batchSize);
    }

    @PreDestroy
    public void stop() {
        log.info("Stopping OutboxDispatcher...");
        running = false;
        if (dispatcherThread != null) {
            try {
                dispatcherThread.join(5000); // Wait up to 5 seconds
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("OutboxDispatcher stopped");
    }

    /**
     * Main dispatch loop - continuously polls and publishes outbox events.
     */
    private void runLoop() {
        while (running) {
            try {
                List<OutboxEvent> pending = outboxRepo.findPending(batchSize);

                if (pending.isEmpty()) {
                    Thread.sleep(pollIntervalMs);
                    continue;
                }

                log.debug("Processing {} pending outbox events", pending.size());

                for (OutboxEvent event : pending) {
                    processOutboxEvent(event);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("OutboxDispatcher interrupted");
                break;
            } catch (Exception e) {
                log.error("Error in outbox dispatch loop", e);
                try {
                    Thread.sleep(pollIntervalMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /**
     * Process a single outbox event: deserialize, publish to Kafka, update status.
     *
     * Uses portfolioId as the Kafka partition key to ensure all trades for
     * a portfolio go to the same partition, preserving order.
     */
    private void processOutboxEvent(OutboxEvent event) {
        try {
            // Deserialize Protobuf payload
            TradeEventProto proto = TradeEventProto.parseFrom(event.getPayload());

            // Use portfolioId as partition key for ordering
            String key = event.getPortfolioId().toString();

            // Synchronous send to ensure ordering and detect failures immediately
            kafkaTemplate.send(tradeTopic, key, proto).get();

            // Mark as sent
            outboxRepo.markSent(event.getId());

            log.trace("Published outbox event: id={}, portfolio={}, trade={}",
                    event.getId(), event.getPortfolioId(), event.getTradeId());

        } catch (Exception e) {
            log.error("Failed to publish outbox event: id={}", event.getId(), e);

            // Increment attempt counter
            outboxRepo.incrementAttempts(event.getId());

            // TODO: Consider exponential backoff or moving to DLQ after max attempts
        }
    }
}
