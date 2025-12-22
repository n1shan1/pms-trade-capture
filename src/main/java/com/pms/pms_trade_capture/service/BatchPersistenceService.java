package com.pms.pms_trade_capture.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;

@Component
public class BatchPersistenceService {
    private static final Logger log = LoggerFactory.getLogger(BatchPersistenceService.class);

    private final SafeStoreRepository safeStoreRepository;
    private final OutboxRepository outboxRepository;
    private final DlqRepository dlqRepository;
    private final AppMetrics metrics;

    private static final String CB_NAME = "pmsDb";

    public BatchPersistenceService(SafeStoreRepository safeStoreRepository,
            OutboxRepository outboxRepository,
            DlqRepository dlqRepository,
            AppMetrics metrics) {
        this.safeStoreRepository = safeStoreRepository;
        this.outboxRepository = outboxRepository;
        this.dlqRepository = dlqRepository;
        this.metrics = metrics;
    }

    /**
     * Atomically persists a batch of trades to SafeStore and Outbox.
     * 
     * Behavior:
     * - Valid messages: Saved to SafeStore + Outbox (will be published to Kafka)
     * - Invalid messages: Saved to SafeStore (marked invalid) + DLQ, NO Outbox entry
     * 
     * Exceptions:
     * - CallNotPermittedException: Circuit breaker open (DB unavailable) - caller should retry
     * - DataIntegrityViolationException: Duplicate trade_id (idempotent - safe to ignore)
     * - Other exceptions: Transient DB errors - caller should retry
     */
    @Transactional
    @CircuitBreaker(name = CB_NAME)
    public void persistBatch(List<PendingStreamMessage> batch) {
        List<SafeStoreTrade> safeTrades = new ArrayList<>();
        List<OutboxEvent> outboxEvents = new ArrayList<>();
        for (PendingStreamMessage msg : batch) {
            prepareEntities(msg, safeTrades, outboxEvents);
        }
        if (!safeTrades.isEmpty())
            safeStoreRepository.saveAll(safeTrades);
        if (!outboxEvents.isEmpty())
            outboxRepository.saveAll(outboxEvents);
        metrics.incrementIngestSuccess(safeTrades.size());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @CircuitBreaker(name = CB_NAME)
    public boolean persistSingleSafely(PendingStreamMessage msg) {
        try {
            List<SafeStoreTrade> safeTrades = new ArrayList<>();
            List<OutboxEvent> outboxEvents = new ArrayList<>();
            prepareEntities(msg, safeTrades, outboxEvents);

            if (!safeTrades.isEmpty())
                safeStoreRepository.saveAll(safeTrades);
            if (!outboxEvents.isEmpty())
                outboxRepository.saveAll(outboxEvents);

            metrics.incrementIngestSuccess(1);
            return true;
        } catch (Exception e) {
            // Note: If CircuitBreaker is OPEN, this method won't even run.
            // It will throw CallNotPermittedException immediately.
            // Check if it's a "Bad Data" error (not a DB connection error)
            if (e instanceof org.springframework.dao.DataIntegrityViolationException
                    || e instanceof IllegalArgumentException) {
                log.warn("Data Integrity Error for seq {}. Moving to DLQ.", msg.getOffset());
                saveToDlq(msg, "Data Error: " + e.getMessage());
                return false;
            }
            throw e; // Rethrow connection errors to trip the breaker
        }
    }

    private void prepareEntities(PendingStreamMessage msg, List<SafeStoreTrade> safeTrades,
            List<OutboxEvent> outboxEvents) {
        if (msg.isValid()) {
            SafeStoreTrade safeTrade = TradeEventMapper.pendingMessageToSafeStoreTrade(msg);
            safeTrade.setValid(true);
            safeTrades.add(safeTrade);
            TradeEventProto proto = msg.getTrade();
            outboxEvents.add(new OutboxEvent(UUID.fromString(proto.getPortfolioId()),
                    UUID.fromString(proto.getTradeId()),
                    proto.toByteArray()));
        } else {
            safeTrades.add(SafeStoreTrade.createInvalid(msg.getRawMessageBytes()));
            saveToDlq(msg, "Invalid Trade Message detected at offset " + msg.getOffset());
        }
    }

    // --- LEVEL 3: DLQ (Last Line of DB Defense) ---
    // Runs in its own transaction to ensure it commits even if everything else
    // failed.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveToDlq(PendingStreamMessage msg, String errorReason) {
        try {
            DlqEntry entry = new DlqEntry(msg.getRawMessageBytes(), errorReason);
            dlqRepository.save(entry);
            log.warn("Level 3 Success: Message {} saved to DLQ.", msg.getOffset());
        } catch (Exception e) {
            // --- LEVEL 4: NUCLEAR OPTION (Disk Log) ---
            // If we can't write to DB, we MUST log the payload bytes to disk/console.
            log.error("CRITICAL: LEVEL 4 FAILURE. DB IS BROKEN. RAW DATA: {}",
                    bytesToHex(msg.getRawMessageBytes()), e);
            // We do not rethrow. We swallow here because we have logged the data.
            // This allows the stream to move forward instead of looping forever on a broken
            // DB.
        }
    }

    /**
     * Convenience method for direct DLQ persistence (used by backpressure overflow
     * handling).
     * Wraps saveToDlq to provide consistent API for BatchingIngestService.
     */
    public void persistSingleToDlq(PendingStreamMessage msg, String errorReason) {
        saveToDlq(msg, errorReason);
    }

    // Utility for logging raw bytes if DB fails
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes)
            sb.append(String.format("%02X", b));
        return sb.toString();
    }
}