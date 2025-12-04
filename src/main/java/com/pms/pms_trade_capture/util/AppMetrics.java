package com.pms.pms_trade_capture.util;


import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class AppMetrics {
    private final Counter ingestSuccess;
    private final Counter ingestFail;

    public AppMetrics(MeterRegistry registry) {
        this.ingestSuccess = Counter.builder("ingest.success").register(registry);
        this.ingestFail = Counter.builder("ingest.fail").register(registry);
    }

    public void incrementIngestSuccess() { ingestSuccess.increment(); }
    public void incrementIngestFail() { ingestFail.increment(); }
}


