package com.pms.pms_trade_capture.config;

import com.rabbitmq.stream.Environment;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for RabbitMQ Streams (NOT classic RabbitMQ queues).
 *
 * RabbitMQ Streams provide:
 * - Append-only log semantics (similar to Kafka)
 * - Ordering guarantees per partition
 * - High-throughput message ingestion
 * - Multiple consumers can read from the same stream independently
 * - Offset-based consumption with manual commit control
 *
 * IMPORTANT: This configuration is for Streams, NOT AMQP queues.
 * There are NO deliveryTag, Channel, or basicAck concepts here.
 * Instead, we use stream offsets and explicit offset commits.
 *
 * All configuration values are externalized via @Value annotations to support
 * environment-specific deployments without code changes.
 */
@Configuration
public class RabbitStreamConfig {
    @Value("${app.rabbit.stream.host}")
    private String streamHost;

    @Value("${app.rabbit.stream.port}")
    private int streamPort;

    /**
     * -- GETTER --
     *
     * @return The configured stream name from application properties
     */
    @Getter
    @Value("${app.rabbit.stream.name}")
    private String streamName;

    /**
     * -- GETTER --
     *
     * @return The configured consumer name from application properties
     */
    @Getter
    @Value("${app.rabbit.stream.consumer-name}")
    private String consumerName;

    /**
     * Creates the RabbitMQ Stream Environment.
     * This is the entry point for connecting to RabbitMQ Streams.
     *
     * The Environment manages the connection lifecycle and provides
     * methods to create producers and consumers.
     */
    @Bean(destroyMethod = "close")
    public Environment rabbitStreamEnvironment() {
        return Environment.builder()
                .host(streamHost)
                .port(streamPort)
                .build();
    }

    /**
     * Declares the stream if it doesn't exist.
     * This ensures the stream is available before any producers or consumers try to use it.
     */
    @Bean
    public String streamDeclaration(Environment rabbitStreamEnvironment) {
        try {
            rabbitStreamEnvironment.streamCreator().stream(streamName).create();
            return "stream-declared";
        } catch (Exception e) {
            // Stream might already exist, ignore
            return "stream-already-exists";
        }
    }

}
