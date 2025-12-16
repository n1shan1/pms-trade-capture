package com.pms.pms_trade_capture.outbox;

import java.util.concurrent.ExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.google.protobuf.InvalidProtocolBufferException;
import com.pms.pms_trade_capture.domain.OutboxEvent;
import com.pms.pms_trade_capture.repository.OutboxRepository;
import com.pms.trade_capture.proto.TradeEventProto;

@Component
public class OutboxEventProcessor {
    private static final Logger log = LoggerFactory.getLogger(OutboxEventProcessor.class);

    private final OutboxRepository repository;
    private final KafkaTemplate<String, TradeEventProto> kafkaTemplate;

    @Value("${app.outbox.trade-topic}")
    private String tradeTopic;

    public OutboxEventProcessor(OutboxRepository repository, KafkaTemplate<String, TradeEventProto> kafkaTemplate) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
    }

    public void process(OutboxEvent event)
            throws ExecutionException, InterruptedException, InvalidProtocolBufferException {
        // 1. Deserialize
        TradeEventProto proto = TradeEventProto.parseFrom(event.getPayload());

        log.debug("Processing Outbox Event ID: {}, Trade ID: {}", event.getId(), proto.getTradeId());

        // 2. Partition Key = Portfolio ID (Guarantees Kafka Ordering)
        String key = event.getPortfolioId().toString();

        // 3. Sync Send (Blocking)
        // We must wait for Kafka Ack before updating the DB to ensure At-Least-Once
        // delivery.
        kafkaTemplate.send(tradeTopic, key, proto).get();
        log.debug("Successfully sent to Kafka. Topic: {}", tradeTopic);

        // 4. DB Ack
        repository.markSent(event.getId());
    }
}
