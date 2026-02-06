package com.pms.pms_trade_capture.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class RttmTelemetryService {

    private final KafkaTemplate<String, String> rttmKafkaTemplate;
    
    @Value("${rttm.client.kafka.topics.trade-events:rttm.trade.events}")
    private String tradeEventsTopic;

    public void sendTradePersisted(String tradeId) {
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("tradeId", tradeId);
            event.put("serviceName", "pms-trade-capture");
            event.put("eventType", "TRADE_PERSISTED");
            event.put("eventStage", "PERSISTED");
            event.put("eventStatus", "SAVED");
            event.put("message", "Trade persisted to database");
            event.put("eventTime", System.currentTimeMillis());
            
            rttmKafkaTemplate.send(tradeEventsTopic, tradeId, event.toString());
            log.debug("Sent RTTM event for trade: {}", tradeId);
        } catch (Exception e) {
            log.error("Failed to send RTTM event for tradeId: {}", tradeId, e);
        }
    }
}
