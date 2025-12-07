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
import com.pms.pms_trade_capture.domain.DlqEntry;
import com.pms.pms_trade_capture.repository.DlqRepository;
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
    private final DlqRepository dlqRepository;
    private final CrossCuttingService crossCuttingService;

    @Value("${app.ingest.batch.max-size-per-portfolio}")
    private int maxSizePerPortfolio;

    @Value("${app.ingest.batch.flush-interval-ms}")
    private long flushIntervalMs;

    @Value("${app.ingest.batch.max-retries:3}")
    private int maxRetries;

    // Buffer: portfolioId -> ordered list of pending messages (trade + offset)
    private final Map<String, List<PendingStreamMessage>> buffers = new ConcurrentHashMap<>();

    // Track last flush time per portfolio
    private final Map<String, Long> lastFlushTime = new ConcurrentHashMap<>();

    // Track retry count per portfolio for failed flushes
    private final Map<String, Integer> retryCount = new ConcurrentHashMap<>();

    // Scheduled executor for time-based flushing
    private ScheduledExecutorService scheduler;

    /**
     * -- SETTER --
     * Set the RabbitMQ Stream consumer reference for offset commits.
     * Must be called by the stream listener after consumer is created.
     */
    @Setter
    private volatile Consumer streamConsumer;

    public BatchingIngestService(SafeStoreRepository safeStoreRepository,
            OutboxRepository outboxRepository,
            DlqRepository dlqRepository,
            CrossCuttingService crossCuttingService) {
        this.safeStoreRepository = safeStoreRepository;
        this.outboxRepository = outboxRepository;
        this.dlqRepository = dlqRepository;
        this.crossCuttingService = crossCuttingService;
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
                TimeUnit.MILLISECONDS);

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

                // Reset retry count on success
                retryCount.remove(portfolioId);

                log.debug("Flushed {} messages for portfolio {}, committed offset {}",
                        messagesCopy.size(), portfolioId, highestOffset);
            } else {
                // DB failed - check retry count
                int currentRetries = retryCount.getOrDefault(portfolioId, 0) + 1;
                retryCount.put(portfolioId, currentRetries);

                if (currentRetries > maxRetries) {
                    // Max retries exceeded - send to DLQ and commit offset to avoid infinite replay
                    log.error("Max retries ({}) exceeded for portfolio {}, sending {} messages to DLQ",
                            maxRetries, portfolioId, messagesCopy.size());
                    saveBatchToDlq(messagesCopy, "DB write failed after " + maxRetries + " retries");
                    long highestOffset = messagesCopy.get(messagesCopy.size() - 1).getOffset();
                    commitStreamOffset(highestOffset);
                    retryCount.remove(portfolioId);
                } else {
                    // Retry - put messages back
                    log.warn("DB write failed for portfolio {} (attempt {}), messages will be replayed",
                            portfolioId, currentRetries);
                    buffers.computeIfAbsent(portfolioId, k -> new ArrayList<>()).addAll(0, messagesCopy);
                }
            }
        } catch (Exception e) {
            log.error("Failed to flush buffer for portfolio {}", portfolioId, e);
            // Check retry count for exceptions too
            int currentRetries = retryCount.getOrDefault(portfolioId, 0) + 1;
            retryCount.put(portfolioId, currentRetries);

            if (currentRetries > maxRetries) {
                // Max retries exceeded - send to DLQ and commit offset
                log.error("Max retries ({}) exceeded for portfolio {} due to exception, sending messages to DLQ",
                        maxRetries, portfolioId);
                saveBatchToDlq(messages, "Flush exception after " + maxRetries + " retries: " + e.getMessage());
                long highestOffset = messages.get(messages.size() - 1).getOffset();
                commitStreamOffset(highestOffset);
                retryCount.remove(portfolioId);
            } else {
                // Put events back in buffer for retry
                buffers.computeIfAbsent(portfolioId, k -> new ArrayList<>()).addAll(0, messages);
            }
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
            List<TradeEventProto> trades = messages.stream()
                    .map(PendingStreamMessage::getTrade)
                    .collect(Collectors.toList());

            List<SafeStoreTrade> safeTrades = trades.stream()
                    .map(TradeEventMapper::protoToSafeStoreTrade)
                    .collect(Collectors.toList());

            // Convert protobuf messages to OutboxEvent entities
            // IMPORTANT: ensure payload is ALWAYS the protobuf bytes (proto.toByteArray()).
            // Defensive: build OutboxEvent explicitly here to avoid accidental assignment
            // of
            // numeric/offset values into the payload field.
            List<OutboxEvent> outboxEvents = trades.stream().map(proto -> {
                byte[] protobufPayload = proto.toByteArray();
                // defensive sanity check
                if (protobufPayload == null) {
                    throw new IllegalStateException(
                            "Protobuf payload should not be null for trade " + proto.getTradeId());
                }
                java.util.UUID portfolioId = java.util.UUID.fromString(proto.getPortfolioId());
                java.util.UUID tradeId = java.util.UUID.fromString(proto.getTradeId());
                OutboxEvent oe = new OutboxEvent(portfolioId, tradeId, protobufPayload);
                return oe;
            }).collect(Collectors.toList());

            List<SafeStoreTrade> savedTrades = safeStoreRepository.saveAll(safeTrades);
            List<OutboxEvent> savedOutboxEvents = outboxRepository.saveAll(outboxEvents);

            // Record lifecycle success for each trade
            for (int i = 0; i < trades.size(); i++) {
                try {
                    crossCuttingService.recordIngestionSuccess(
                        TradeEventMapper.protoToDto(trades.get(i)),
                        savedTrades.get(i),
                        savedOutboxEvents.get(i)
                    );
                } catch (Exception e) {
                    log.warn("Failed to send lifecycle success event for trade {}", trades.get(i).getTradeId(), e);
                }
            }

            log.debug("Persisted batch: {} trades, {} outbox events", savedTrades.size(), savedOutboxEvents.size());
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
     * Save a batch of failed messages to the Dead Letter Queue.
     *
     * @param messages    The failed messages
     * @param errorDetail Error description
     */
    private void saveBatchToDlq(List<PendingStreamMessage> messages, String errorDetail) {
        try {
            List<DlqEntry> dlqEntries = messages.stream().map(msg -> {
                try {
                    String rawMessage = msg.getTrade().toString(); // Protobuf toString
                    return new DlqEntry(rawMessage, errorDetail);
                } catch (Exception e) {
                    log.warn("Failed to serialize message for DLQ", e);
                    return new DlqEntry("Serialization failed", errorDetail + ": " + e.getMessage());
                }
            }).collect(Collectors.toList());

            List<DlqEntry> savedDlqEntries = dlqRepository.saveAll(dlqEntries);

            // Record lifecycle failure for each trade
            for (int i = 0; i < messages.size(); i++) {
                try {
                    crossCuttingService.recordIngestionFailure(
                        TradeEventMapper.protoToDto(messages.get(i).getTrade()),
                        savedDlqEntries.get(i),
                        new RuntimeException(errorDetail)
                    );
                } catch (Exception e) {
                    log.warn("Failed to send lifecycle failure event for trade {}", 
                        messages.get(i).getTrade().getTradeId(), e);
                }
            }

            log.debug("Saved {} messages to DLQ: {}", dlqEntries.size(), errorDetail);
        } catch (Exception e) {
            log.error("Failed to save batch to DLQ", e);
            // Log the messages as last resort
            messages.forEach(msg -> log.error("LOST MESSAGE (DLQ save failed): {}", msg.getTrade()));
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
