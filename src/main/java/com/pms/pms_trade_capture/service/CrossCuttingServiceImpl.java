package com.pms.pms_trade_capture.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.pms_trade_capture.domain.DlqEntry;
import com.pms.pms_trade_capture.domain.OutboxEvent;
import com.pms.pms_trade_capture.domain.SafeStoreTrade;
import com.pms.pms_trade_capture.dto.TradeEventDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CrossCuttingServiceImpl implements CrossCuttingService {
    private static final String SERVICE_NAME = "PMS_TRADE_CAPTURE";
    private static final String STAGE_INGESTION = "INGESTION";

    private final KafkaTemplate<String, String> lifecycleKafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.kafka.lifecycle-topic:lifecycle.event}")
    private String lifecycleTopic;

    @Override
    public void recordIngestionSuccess(TradeEventDto tradeEvent,
                                       SafeStoreTrade safeStoreTrade,
                                       OutboxEvent outboxEvent) {

        Map<String, Object> details = new HashMap<>();
        details.put("sourceService", SERVICE_NAME);
        details.put("eventType", "INGESTION_PERSISTED");
        details.put("safeStoreId", safeStoreTrade.getId());
        details.put("outboxId", outboxEvent.getId());
        details.put("receivedAt", safeStoreTrade.getReceivedAt());
        details.put("createdAt", outboxEvent.getCreatedAt());

        sendLifecycleEvent(
                tradeEvent,
                STAGE_INGESTION,
                "SUCCESS",
                details
        );
    }

    @Override
    public void recordIngestionFailure(TradeEventDto tradeEvent,
                                       DlqEntry dlqEntry,
                                       Exception ex) {

        Map<String, Object> details = new HashMap<>();
        details.put("sourceService", SERVICE_NAME);
        details.put("eventType", "INGESTION_FAILED");
        details.put("dlqId", dlqEntry.getId());
        details.put("failedAt", dlqEntry.getFailedAt());
        details.put("errorMessage", ex.getMessage());
        details.put("exceptionType", ex.getClass().getName());

        sendLifecycleEvent(
                tradeEvent,
                STAGE_INGESTION,
                "FAILURE",
                details
        );
    }

    private void sendLifecycleEvent(TradeEventDto tradeEvent,
                                    String stage,
                                    String status,
                                    Map<String, Object> details) {

        Map<String, Object> payload = new HashMap<>();
        payload.put("traceId", tradeEvent.getTradeId());
        payload.put("portfolioId", tradeEvent.getPortfolioId());
        payload.put("stage", stage);
        payload.put("status", status);
        payload.put("ts", Instant.now());
        payload.put("details", details);

        try {
            String json = objectMapper.writeValueAsString(payload);
            String key = tradeEvent.getTradeId();

            lifecycleKafkaTemplate.send(lifecycleTopic, key, json)
                    .whenComplete((result, ex) -> {
                        if (ex == null) {
                            log.debug("Lifecycle event sent: key={}, status={}, stage={}, offset={}",
                                    key, status, stage, result.getRecordMetadata().offset());
                        } else {
                            log.warn("Failed to send lifecycle event: key={}, status={}, stage={} - {}",
                                    key, status, stage, ex.getMessage());
                        }
                    });

        } catch (JsonProcessingException e) {
            log.error("Failed to serialize lifecycle event for trade {}", tradeEvent.getTradeId(), e);
        } catch (Exception e) {
            log.error("Failed to send lifecycle event to Kafka for trade {} - Error: {}",
                    tradeEvent.getTradeId(), e.getMessage(), e);
        }
    }
}
