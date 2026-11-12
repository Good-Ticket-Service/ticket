package com.sparta.ticket.payment.infrastructure.messaging;

import java.util.HashMap;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;

@Configuration
@EnableConfigurationProperties(SettlementPostingKafkaProperties.class)
@ConditionalOnProperty(name = "payment.settlement.posting.kafka.enabled", havingValue = "true")
public class SettlementPostingKafkaConfiguration {
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> settlementPostingContainerFactory(
            KafkaProperties kafka, SettlementPostingKafkaProperties config) {
        var values = new HashMap<String, Object>(kafka.buildConsumerProperties(null));
        values.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        values.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        values.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        values.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        values.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        values.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, config.getMaxPollRecords());
        values.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 600000);
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(values));
        factory.setConcurrency(config.getConcurrency());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.getContainerProperties().setAsyncAcks(false);
        factory.getContainerProperties().setSyncCommits(true);
        // Note: 격리/DLT가 인수하지 않은 오류는 컨테이너를 중지하고 offset을 보존한다.
        factory.setCommonErrorHandler(new CommonContainerStoppingErrorHandler());
        return factory;
    }

    @Bean
    NewTopic settlementPostingTopic(SettlementPostingKafkaProperties config) {
        return TopicBuilder.name(config.getTopic())
                .partitions(6)
                .replicas(1)
                .config("retention.ms", "604800000")
                .build();
    }

    @Bean
    NewTopic settlementPostingDeadLetterTopic(SettlementPostingKafkaProperties config) {
        return TopicBuilder.name(config.getTopic() + ".DLT")
                .partitions(6)
                .replicas(1)
                .config("retention.ms", "1209600000")
                .build();
    }
}
