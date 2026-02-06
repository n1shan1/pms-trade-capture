package com.pms.pms_trade_capture.config;

import com.google.protobuf.MessageLite;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

import com.pms.rttm.client.clients.KafkaRttmClient;
import com.pms.rttm.client.clients.RttmClient;
import com.pms.rttm.client.config.RttmClientConfig;

@Configuration
public class RttmClientConfiguration {

	@Bean
	public RttmClient rttmClient(
			KafkaTemplate<String, MessageLite> kafkaTemplate,
			RttmClientProperties properties) {

		RttmClientConfig config = RttmClientConfig.builder()
				.mode(properties.getMode())
				.kafkaBootstrapServers(properties.getKafka().getBootstrapServers())
				.kafkaTopicTradeEvents(properties.getKafka().getTopics().getTradeEvents())
				.kafkaTopicDlqEvents(properties.getKafka().getTopics().getDlqEvents())
				.kafkaTopicQueueMetrics(properties.getKafka().getTopics().getQueueMetrics())
				.kafkaTopicErrorEvents(properties.getKafka().getTopics().getErrorEvents())
				.sendTimeoutMs(properties.getSendTimeoutMs())
				.retryMaxAttempts(properties.getRetry().getMaxAttempts())
				.retryBackoffMs(properties.getRetry().getBackoffMs())
				.build();

		return KafkaRttmClient.builder()
				.kafkaTemplate(kafkaTemplate)
				.config(config)
				.build();
	}
}
