package com.sparta.ticket.payment.infrastructure.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** #319의 결과 토픽은 기본 자동 생성 파티션 수에 의존하지 않는다. RF1은 현 로컬 구성이다. */
@Configuration
@ConditionalOnProperty(name = "payment.messaging.result-relay.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentResultTopics {
    @Bean
    NewTopic paymentResultTopic() {
        return TopicBuilder.name(PaymentResultRelay.TOPIC)
                .partitions(12)
                .replicas(1)
                .build();
    }

    @Bean
    NewTopic paymentResultDeadLetterTopic() {
        return TopicBuilder.name(PaymentResultRelay.TOPIC + ".DLT")
                .partitions(12)
                .replicas(1)
                .build();
    }
}
