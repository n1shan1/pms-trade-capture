package com.pms.pms_trade_capture.service;

import com.rabbitmq.stream.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

@Component
public class StreamOffsetManager {
    private static final Logger log = LoggerFactory.getLogger(StreamOffsetManager.class);

    private final AtomicReference<Consumer> consumerRef = new AtomicReference<>();

    public void setStreamConsumer(Consumer consumer) {
        this.consumerRef.set(consumer);
    }

    public void commit(long offset) {
        Consumer consumer = consumerRef.get();
        if (consumer == null) {
            log.warn("Cannot commit offset {}: Consumer not yet registered", offset);
            return;
        }

        try {
            consumer.store(offset);
            log.debug("Committed offset: {}", offset);
        } catch (Exception e) {
            // If storing offset fails, we log but DO NOT throw.
            // Worst case: we replay this batch on restart.
            // This is safer than crashing the app.
            log.error("Failed to commit stream offset {}", offset, e);
        }
    }

}
