package com.pms.pms_trade_capture.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "rttm.client")
@Data
public class RttmClientProperties {

    private String mode;
    private int sendTimeoutMs;
    private Kafka kafka = new Kafka();
    private Retry retry = new Retry();

    @Data
    public static class Kafka {
        private String bootstrapServers;
        private Topics topics = new Topics();

        @Data
        public static class Topics {
            private String tradeEvents;
            private String dlqEvents;
            private String queueMetrics;
            private String errorEvents;
        }
    }

    @Data
    public static class Retry {
        private int maxAttempts;
        private int backoffMs;
    }
}
