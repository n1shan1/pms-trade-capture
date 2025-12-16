package com.pms.pms_trade_capture.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import com.pms.pms_trade_capture.domain.DlqEntry;
import com.pms.pms_trade_capture.domain.OutboxEvent;
import com.pms.pms_trade_capture.domain.PendingStreamMessage;
import com.pms.pms_trade_capture.domain.SafeStoreTrade;
import com.pms.pms_trade_capture.dto.TradeEventMapper;
import com.pms.pms_trade_capture.repository.DlqRepository;
import com.pms.pms_trade_capture.repository.OutboxRepository;
import com.pms.pms_trade_capture.repository.SafeStoreRepository;
import com.pms.pms_trade_capture.utils.AppMetrics;
import com.pms.trade_capture.proto.TradeEventProto;

import org.springframework.transaction.support.TransactionTemplate;

import jakarta.transaction.Transactional;

@Component
public class BatchPersistenceService {
    private static final Logger log = LoggerFactory.getLogger(BatchPersistenceService.class);

    private final SafeStoreRepository safeStoreRepository;
    private final OutboxRepository outboxRepository;
    private final DlqRepository dlqRepository;
    private final AppMetrics metrics;
    private final TransactionTemplate transactionTemplate;

    public BatchPersistenceService(SafeStoreRepository safeStoreRepository,
            OutboxRepository outboxRepository,
            DlqRepository dlqRepository,
            AppMetrics metrics,
            TransactionTemplate transactionTemplate) {
        this.safeStoreRepository = safeStoreRepository;
        this.outboxRepository = outboxRepository;
        this.dlqRepository = dlqRepository;
        this.metrics = metrics;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Atomically persists a batch of trades to SafeStore and Outbox.
     * Returns TRUE if successful (or idempotent duplicate), FALSE if retriable
     * error.
     */
    @Transactional
    public boolean persistBatch(List<PendingStreamMessage> batch) {
        try {
            List<SafeStoreTrade> safeTrades = new ArrayList<>();
            List<OutboxEvent> outboxEvents = new ArrayList<>();
            List<DlqEntry> invalidEntries = new ArrayList<>();

            for (PendingStreamMessage msg : batch) {
                if (msg.isValid()) {
                    // 1. Valid Path: Real SafeStore + Outbox
                    SafeStoreTrade safeTrade = TradeEventMapper.pendingMessageToSafeStoreTrade(msg);
                    safeTrade.setRawPayload(msg.getRawMessageBytes()); // Ensure payload is stored
                    safeTrades.add(safeTrade);

                    TradeEventProto proto = msg.getTrade();
                    outboxEvents.add(new OutboxEvent(
                            UUID.fromString(proto.getPortfolioId()),
                            UUID.fromString(proto.getTradeId()),
                            proto.toByteArray()));
                } else {
                    // --- INVALID DATA PATH ---
                    // 1. Audit Log (Marked Invalid, Sentinel Values)
                    safeTrades.add(SafeStoreTrade.createInvalid(msg.getRawMessageBytes()));

                    // 2. Dead Letter Queue (Alerting)
                    // We DO NOT put this in Outbox. It will never go to Kafka.
                    invalidEntries.add(new DlqEntry(msg.getRawMessageBytes(), "Ingest: Invalid Protocol Buffer"));
                }
            }

            // Bulk Inserts
            if (!safeTrades.isEmpty())
                safeStoreRepository.saveAll(safeTrades);
            if (!outboxEvents.isEmpty())
                outboxRepository.saveAll(outboxEvents);
            if (!invalidEntries.isEmpty()) {
                transactionTemplate.execute(status -> {
                    dlqRepository.saveAll(invalidEntries);
                    return null;
                });
            }

            metrics.incrementIngestSuccess(safeTrades.size());
            return true;
            // // 1. Filter the valid Messages
            // List<PendingStreamMessage> validMessages = batch.stream()
            // .filter(PendingStreamMessage::isValid)
            // .toList();
            // // 2. if nothing to save return
            // if(validMessages.isEmpty()) return true;
            // // 3. Map to entity
            // List<SafeStoreTrade> safeTrades = validMessages.stream()
            // .map(TradeEventMapper::pendingMessageToSafeStoreTrade)
            // .collect(Collectors.toList());
            // List<OutboxEvent> outboxEvents = validMessages.stream()
            // .map(msg -> {
            // TradeEventProto proto = msg.getTrade();
            // return new OutboxEvent(
            // UUID.fromString(proto.getPortfolioId()),
            // UUID.fromString(proto.getTradeId()),
            // proto.toByteArray()
            // );
            // })
            // .toList();
            // // 3. Batch Insert
            // safeStoreRepository.saveAll(safeTrades);
            // outboxRepository.saveAll(outboxEvents);
            // // 4. Record Success Metrics
            // metrics.incrementIngestSuccess(validMessages.size());
            // return true;
        } catch (DataIntegrityViolationException e) {
            // Idempotency Handler:
            // If we crash after DB commit but before Ack, RabbitMQ resends the batch.
            // We catch the duplicate key error and treat it as "Success" so we can Ack.
            log.warn("Duplicate batch detected (idempotent replay). Marking as success. Error: {}", e.getMessage());
            return true;
        }
    }

    /**
     * Persists a batch to the DLQ in a separate transaction.
     * Used when the main path fails catastrophically.
     */
    @Transactional
    public void persistToDlq(List<PendingStreamMessage> batch, String reason) {
        try {
            List<DlqEntry> entries = batch.stream()
                    .map(msg -> new DlqEntry(msg.getRawMessageBytes(), reason))
                    .collect(Collectors.toList());

            dlqRepository.saveAll(entries);
            log.info("Persisted {} failed messages to DLQ. Reason: {}", entries.size(), reason);
        } catch (Exception e) {
            log.error("CRITICAL: Failed to write to DLQ. Data potential loss.", e);
            throw new RuntimeException("Failed to write to DLQ", e);
        }
    }
}
