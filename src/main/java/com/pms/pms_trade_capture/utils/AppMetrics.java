package com.pms.pms_trade_capture.utils;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class AppMetrics {
    private final Counter ingestSuccess;
    private final Counter ingestFail;
    private final Counter dlqWrites;

    public AppMetrics(MeterRegistry registry) {
        this.ingestSuccess = Counter.builder("trade.ingest.success")
                .description("Number of trades successfully persisted to DB")
                .register(registry);

        this.ingestFail = Counter.builder("trade.ingest.fail")
                .description("Number of trades failed to persist (retried or lost)")
                .register(registry);

        this.dlqWrites = Counter.builder("trade.ingest.dlq")
                .description("Number of poisonous messages sent to DLQ")
                .register(registry);
    }

    public void incrementIngestSuccess(int count) {
        ingestSuccess.increment(count);
    }

    public void incrementIngestFail(int count) {
        ingestFail.increment(count);
    }

    public void incrementDlq(int count) {
        dlqWrites.increment(count);
    }
}
