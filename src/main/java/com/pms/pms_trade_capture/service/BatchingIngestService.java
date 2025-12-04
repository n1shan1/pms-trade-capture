package com.pms.pms_trade_capture.service;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.pms.pms_trade_capture.domain.OutboxEvent;
import com.pms.pms_trade_capture.domain.SafeStoreTrade;
import com.pms.pms_trade_capture.dto.TradeEventMapper;
import com.pms.pms_trade_capture.repository.OutboxRepository;
import com.pms.pms_trade_capture.repository.SafeStoreRepository;
import com.pms.pms_trade_capture.stream.PendingStreamMessage;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import com.rabbitmq.stream.Consumer;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.transaction.Transactional;
import com.pms.pms_trade_capture.proto.TradeEventProto;

@Service
public class BatchingIngestService {
    private static final Logger log = LoggerFactory.getLogger(BatchingIngestService.class);

    private final SafeStoreRepository safeStoreRepository;
    private final OutboxRepository outboxRepository;

    @Value("${app.ingest.batch.max-size-per-portfolio}")
    private int maxSizePerPortfolio;

    @Value("${app.ingest.batch.flush-interval-ms}")
    private long flushIntervalMs;

    // Buffer: portfolioId -> ordered list of pending messages (trade + offset)
    private final Map<String, List<PendingStreamMessage>> buffers = new ConcurrentHashMap<>();

    // Track last flush time per portfolio
    private final Map<String, Long> lastFlushTime = new ConcurrentHashMap<>();

    // Scheduled executor for time-based flushing
    private ScheduledExecutorService scheduler;

    /**
     * -- SETTER --
     *  Set the RabbitMQ Stream consumer reference for offset commits.
     *  Must be called by the stream listener after consumer is created.
     */
    // Reference to RabbitMQ Stream consumer for offset commits
    @Setter
    private volatile Consumer streamConsumer;

    public BatchingIngestService(SafeStoreRepository safeStoreRepository,
                                 OutboxRepository outboxRepository) {
        this.safeStoreRepository = safeStoreRepository;
        this.outboxRepository = outboxRepository;
    }

    @PostConstruct
    public void init() {
        // Start scheduled task to flush based on time threshold
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "batch-flush-scheduler");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleWithFixedDelay(
                this::flushStaleBuffers,
                flushIntervalMs,
                flushIntervalMs / 2, // Check twice as often as the threshold
                TimeUnit.MILLISECONDS
        );

        log.info("BatchingIngestService initialized: maxSize={}, flushInterval={}ms",
                maxSizePerPortfolio, flushIntervalMs);
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down BatchingIngestService, flushing all buffers...");
        flushAllBuffers();
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    /**
     * Add a trade event with its stream offset to the buffer.
     * Flushes if buffer reaches max size.
     *
     * @param pendingMessage The pending message containing trade and offset
     */
    public void addMessage(PendingStreamMessage pendingMessage) {
        String portfolioId = pendingMessage.getTrade().getPortfolioId();

        synchronized (this) {
            buffers.computeIfAbsent(portfolioId, k -> new ArrayList<>()).add(pendingMessage);
            lastFlushTime.putIfAbsent(portfolioId, System.currentTimeMillis());

            List<PendingStreamMessage> buffer = buffers.get(portfolioId);

            // Flush if buffer reaches max size
            if (buffer.size() >= maxSizePerPortfolio) {
                flushBuffer(portfolioId);
            }
        }
    }

    /**
     * Flush buffers that have exceeded the time threshold
     */
    private void flushStaleBuffers() {
        long now = System.currentTimeMillis();
        List<String> portfoliosToFlush = new ArrayList<>();

        synchronized (this) {
            for (Map.Entry<String, Long> entry : lastFlushTime.entrySet()) {
                String portfolioId = entry.getKey();
                long lastFlush = entry.getValue();

                if (now - lastFlush >= flushIntervalMs &&
                        !buffers.getOrDefault(portfolioId, Collections.emptyList()).isEmpty()) {
                    portfoliosToFlush.add(portfolioId);
                }
            }

            for (String portfolioId : portfoliosToFlush) {
                flushBuffer(portfolioId);
            }
        }
    }

    /**
     * Flush all buffers (called on shutdown)
     */
    private void flushAllBuffers() {
        synchronized (this) {
            new ArrayList<>(buffers.keySet()).forEach(this::flushBuffer);
        }
    }

    /**
     * Flush a specific portfolio's buffer to the database.
     * This method must be called while holding the monitor lock.
     *
     * CRITICAL: Offset commit happens AFTER DB commit succeeds.
     *
     * @param portfolioId The portfolio ID to flush
     */
    private void flushBuffer(String portfolioId) {
        List<PendingStreamMessage> messages = buffers.get(portfolioId);
        if (messages == null || messages.isEmpty()) {
            return;
        }

        try {
            // Create copies to avoid holding lock during DB operation
            List<PendingStreamMessage> messagesCopy = new ArrayList<>(messages);
            messages.clear();
            lastFlushTime.put(portfolioId, System.currentTimeMillis());

            // Perform DB write (this releases the lock during I/O)
            boolean dbSuccess = writeBatchToDatabase(messagesCopy);

            if (dbSuccess) {
                // CRITICAL: Commit stream offset ONLY after DB success
                long highestOffset = messagesCopy.get(messagesCopy.size() - 1).getOffset();
                commitStreamOffset(highestOffset);

                log.debug("Flushed {} messages for portfolio {}, committed offset {}",
                        messagesCopy.size(), portfolioId, highestOffset);
            } else {
                // DB failed - do NOT commit offset, put messages back
                log.warn("DB write failed for portfolio {}, messages will be replayed", portfolioId);
                buffers.computeIfAbsent(portfolioId, k -> new ArrayList<>()).addAll(0, messagesCopy);
            }
        } catch (Exception e) {
            log.error("Failed to flush buffer for portfolio {}", portfolioId, e);
            // Put events back in buffer for retry
            buffers.computeIfAbsent(portfolioId, k -> new ArrayList<>()).addAll(0, messages);
        }
    }

    /**
     * Write a batch of messages to the database in a single transaction.
     * Uses saveAll() for both tables to minimize DB round-trips.
     *
     * Handles duplicate keys (replayed messages) gracefully via idempotency.
     *
     * @param messages List of pending messages to persist
     * @return true if DB write succeeded, false otherwise
     */
    @Transactional
    public boolean writeBatchToDatabase(List<PendingStreamMessage> messages) {
        try {
            // Extract trade protos from pending messages
            List<TradeEventProto> trades = messages.stream()
                    .map(PendingStreamMessage::getTrade)
                    .collect(Collectors.toList());

            // Convert protobuf messages to SafeStoreTrade entities
            List<SafeStoreTrade> safeTrades = trades.stream()
                    .map(TradeEventMapper::protoToSafeStoreTrade)
                    .collect(Collectors.toList());

            // Convert protobuf messages to OutboxEvent entities
            // IMPORTANT: ensure payload is ALWAYS the protobuf bytes (proto.toByteArray()).
            // Defensive: build OutboxEvent explicitly here to avoid accidental assignment of
            // numeric/offset values into the payload field.
            List<OutboxEvent> outboxEvents = trades.stream().map(proto -> {
                byte[] protobufPayload = proto.toByteArray();
                // defensive sanity check
                if (protobufPayload == null) {
                    throw new IllegalStateException("Protobuf payload should not be null for trade " + proto.getTradeId());
                }
                java.util.UUID portfolioId = java.util.UUID.fromString(proto.getPortfolioId());
                java.util.UUID tradeId = java.util.UUID.fromString(proto.getTradeId());
                OutboxEvent oe = new OutboxEvent(portfolioId, tradeId, protobufPayload);
                return oe;
            }).collect(Collectors.toList());

            // Batch insert both tables in single transaction
            // Debug: log outbox payload sizes/types to help diagnose DB mapping issues
            for (int i = 0; i < outboxEvents.size(); i++) {
                OutboxEvent oe = outboxEvents.get(i);
                if (oe.getPayload() == null) {
                    log.warn("Outbox event payload null for tradeId={}", oe.getTradeId());
                } else {
                    // use INFO level temporarily to ensure visibility during debugging
                    log.info("Outbox payload debug: tradeId={}, length={}, class={}, firstByte={}",
                            oe.getTradeId(), oe.getPayload().length, oe.getPayload().getClass().getName(),
                            (oe.getPayload().length > 0 ? oe.getPayload()[0] : -1));
                }
            }

            safeStoreRepository.saveAll(safeTrades);
            outboxRepository.saveAll(outboxEvents);

            log.debug("Persisted batch: {} trades, {} outbox events", safeTrades.size(), outboxEvents.size());
            return true;

        } catch (DataIntegrityViolationException e) {
            // Duplicate key - this is expected during replay after crash
            // Message was already persisted before crash, safe to proceed
            log.debug("Duplicate trade_id detected (replay after crash), treating as idempotent: {}",
                    e.getMessage());
            return true; // Return success so offset gets committed

        } catch (Exception e) {
            log.error("Failed to persist batch to database", e);
            return false; // Return failure so offset does NOT get committed
        }
    }

    /**
     * Commit the stream offset to RabbitMQ Streams.
     * This tells the stream that we've successfully processed up to this offset.
     *
     * CRITICAL: This must only be called AFTER successful DB persistence.
     *
     * @param offset The stream offset to commit
     */
    private void commitStreamOffset(long offset) {
        if (streamConsumer == null) {
            log.warn("Stream consumer not set, cannot commit offset {}", offset);
            return;
        }

        try {
            streamConsumer.store(offset);
            log.trace("Committed stream offset: {}", offset);
        } catch (Exception e) {
            log.error("Failed to commit stream offset {}", offset, e);
            // Note: If offset commit fails, stream will replay these messages
            // DB idempotency will handle duplicates
        }
    }
}
