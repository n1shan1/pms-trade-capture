package com.pms.pms_trade_capture.stream;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.pms.pms_trade_capture.config.RabbitStreamConfig;
import com.pms.pms_trade_capture.service.StreamOffsetManager;
import com.rabbitmq.stream.Consumer;
import com.rabbitmq.stream.Environment;
import com.rabbitmq.stream.OffsetSpecification;

@Component
public class StreamConsumerManager implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(StreamConsumerManager.class);

    private final Environment environment;
    private final RabbitStreamConfig rabbitConfig;
    private final TradeStreamHandler tradeStreamHandler;
    private final StreamOffsetManager offsetManager;

    private volatile Consumer consumer;
    private volatile boolean running = false;
    private final AtomicBoolean isPaused = new AtomicBoolean(false);

    public StreamConsumerManager(Environment environment,
            RabbitStreamConfig rabbitConfig,
            TradeStreamHandler tradeStreamHandler,
            StreamOffsetManager offsetManager) {
        this.environment = environment;
        this.rabbitConfig = rabbitConfig;
        this.tradeStreamHandler = tradeStreamHandler;
        this.offsetManager = offsetManager;
    }

    @Override
    public void start() {
        log.info("Starting RabbitMQ stream Consumer: {}", rabbitConfig.getStreamName());
        try {
            this.consumer = environment.consumerBuilder()
                    .stream(rabbitConfig.getStreamName())
                    .name(rabbitConfig.getConsumerName())
                    .offset(OffsetSpecification.first())
                    .messageHandler(tradeStreamHandler)
                    .autoTrackingStrategy()
                    .builder().build();

            offsetManager.setStreamConsumer(consumer);

            this.running = true;

            log.info("Consumer Started Successfully. Listening for trades...");

        } catch (Exception e) {
            // In SmartLifecycle, an exception here will stop the app startup,
            // which is correct behavior (we can't run without the stream).
            log.error("Failed to start RabbitMQ Stream Consumer", e);
            throw new RuntimeException("Stream start failed", e);
        }
    }

    @Override
    public void stop() {
        log.info("Stopping RabbitMQ Stream Consumer...");
        if (consumer != null) {
            consumer.close();
        }
        this.running = false;
        log.info("Consumer stopped.");
    }

    @Override
    public boolean isRunning() {
        return this.running;
    }

    /**
     * Auto-start ensures this bean's start() is called automatically
     * when the context refreshes.
     */
    @Override
    public boolean isAutoStartup() {
        return SmartLifecycle.super.isAutoStartup();
    }

    /**
     * Phase: Lower numbers start first and stop last.
     * We want this to start LATE (after DB) and stop EARLY (before DB).
     * Integer.MAX_VALUE means "Start last, Stop first".
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    /**
     * Pause tracking for backpressure visibility.
     * RabbitMQ Streams don't support consumer.pause() like Kafka.
     * Instead, we handle backpressure at the message handler level
     * by rejecting messages when the buffer is full.
     */
    public void pause() {
        if (isPaused.compareAndSet(false, true)) {
            log.warn("⏸️ BACKPRESSURE ACTIVATED - buffer full or circuit breaker open");
        }
    }

    /**
     * Resume tracking for backpressure visibility.
     */
    public void resume() {
        if (isPaused.compareAndSet(true, false)) {
            log.info("▶️ BACKPRESSURE RELEASED - ready to process");
        }
    }

    /**
     * Check if backpressure is active.
     * Used for monitoring and alerting.
     */
    public boolean isPaused() {
        return isPaused.get();
    }
}
