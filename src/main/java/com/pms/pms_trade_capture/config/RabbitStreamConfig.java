package com.pms.pms_trade_capture.config;

import com.rabbitmq.stream.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;



@Configuration
public class RabbitStreamConfig {
    public static final Logger log = LoggerFactory.getLogger(RabbitStreamConfig.class);

    @Value("${app.rabbit.stream.host:localhost}")
    private String host;

    @Value("${app.rabbit.stream.port:5552}")
    private int port;

    @Value("${app.rabbit.stream.username:guest}")
    private String username;

    @Value("${app.rabbit.stream.password:guest}")
    private String password;

    @Value("${app.rabbit.stream.name:trade-events-stream}")
    private String streamName;

    @Bean(destroyMethod = "close")
    public Environment rabbitStreamEnvironment() {
        log.info("Initializing RabbitMQ Stream Environment connecting to {}:{}", host, port);

        return Environment.builder()
                .host(host)
                .port(port)
                .username(username)
                .password(password)
                .maxConsumersByConnection(1)
                .build();
    }

    @Bean
    public String streamDeclaration(Environment environment) {
        try {
            log.info("Attempting to declare stream: {}", streamName);
            environment.streamCreator()
                    .stream(streamName)
                    // Optional: Configure retention (e.g., 24 hours or 10GB)
                    .maxAge(java.time.Duration.ofHours(24))
                    .create();
            log.info("Stream '{}' created successfully.", streamName);
            return "created";
        } catch (Exception e) {
            // Usually means it already exists, which is fine
            log.info("Stream '{}' already exists or could not be created: {}", streamName, e.getMessage());
            return "existing";
        }
    }




}
