package com.sparta.ticket.payment.infrastructure.messaging;

import com.sparta.ticket.payment.application.service.payment.MessageApprovalProperties;
import com.sparta.ticket.payment.infrastructure.pg.PgBulkheadProperties;
import java.util.HashMap;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;

@Configuration
@ConditionalOnProperty(name = "payment.messaging.confirm.enabled", havingValue = "true")
public class PaymentConfirmKafkaConfiguration {
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> paymentConfirmContainerFactory(
            KafkaProperties kafka,
            MessageApprovalProperties properties,
            PaymentConfirmOwnership ownership,
            PgBulkheadProperties pg) {
        properties.validate();
        if (pg.enabled() && properties.getPerProvider() >= Math.min(pg.tossMaxConcurrent(), pg.kakaoMaxConcurrent()))
            throw new IllegalArgumentException("메시지 승인 상한은 PG 전체 상한보다 작아야 한다. 복구·환불 자리를 남긴다");
        var values = new HashMap<String, Object>(kafka.buildConsumerProperties(null));
        values.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        values.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        values.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        values.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        values.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, properties.getMaxPollRecords());
        values.put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, 1024 * 1024);
        values.put(ConsumerConfig.FETCH_MAX_BYTES_CONFIG, 2 * 1024 * 1024);
        values.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(values));
        factory.setConcurrency(properties.getConcurrency());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.getContainerProperties().setAsyncAcks(true);
        factory.getContainerProperties().setConsumerRebalanceListener(ownership);
        factory.setCommonErrorHandler(new CommonContainerStoppingErrorHandler());
        return factory;
    }

    @Bean
    NewTopic paymentConfirmTopic() {
        return TopicBuilder.name(PaymentConfirmConsumer.TOPIC)
                .partitions(12)
                .replicas(1)
                .config("retention.ms", "86400000")
                .build();
    }

    @Bean
    NewTopic paymentConfirmDeadLetterTopic() {
        return TopicBuilder.name(PaymentConfirmConsumer.TOPIC + ".DLT")
                .partitions(12)
                .replicas(1)
                .config("retention.ms", "604800000")
                .build();
    }
}
