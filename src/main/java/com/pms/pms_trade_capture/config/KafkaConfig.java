package com.pms.pms_trade_capture.config;

import com.google.protobuf.MessageLite;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializer;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import com.pms.trade_capture.proto.TradeEventProto;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConfig {
    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.properties.schema.registry.url}")
    private String schemaRegistryUrl;


    public ProducerFactory<String, TradeEventProto> producerFactory() {
        Map<String, Object> config = new HashMap<>();

        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaProtobufSerializer.class);
        config.put(KafkaProtobufSerializerConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);
        config.put(KafkaProtobufSerializerConfig.AUTO_REGISTER_SCHEMAS, true);

        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
        config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);

        // Throughput Tuning (Batching)
        config.put(ProducerConfig.LINGER_MS_CONFIG, 20);           // Wait up to 20ms to group messages
        config.put(ProducerConfig.BATCH_SIZE_CONFIG, 64 * 1024);   // 64KB batch size
        config.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "gzip");

        return new DefaultKafkaProducerFactory<>(config);

    }
    @Bean
    public KafkaTemplate<String, TradeEventProto> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    	// Generic KafkaTemplate for RTTM Client to send any MessageLite (protobuf
	// messages)
	@Bean
	ProducerFactory<String, MessageLite> messageLiteProducerFactory() {
		Map<String, Object> props = new HashMap<>();
		props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers);
		props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
				io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializer.class);
		props.put("schema.registry.url", schemaRegistryUrl);

		// Retry configuration
		props.put(ProducerConfig.RETRIES_CONFIG, 5);
		props.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, 1000);
		props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30000);
		props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 15000);

		// Safe producer
		props.put(ProducerConfig.ACKS_CONFIG, "all");
		props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);

		return new DefaultKafkaProducerFactory<>(props);
	}

	@Bean
	KafkaTemplate<String, MessageLite> messageLiteKafkaTemplate() {
		return new KafkaTemplate<>(messageLiteProducerFactory());
	}

}
