package com.sparta.ticket.reservation.booking.infrastructure.messaging;

import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
class ScheduleCanceledKafkaConfig {

    static final String TOPIC = "schedule.canceled";
    static final String GROUP_ID = "reservation-booking";
    static final String CONTAINER_FACTORY = "scheduleCanceledContainerFactory";

    private static final String DEAD_LETTER_TOPIC = TOPIC + ".DLT";
    private static final long RETRY_INITIAL_MS = 1_000;
    private static final long RETRY_MAX_MS = 30_000;

    /**
     * 기록을 커밋한 뒤에만 offset을 커밋한다(AckMode.RECORD). 그 사이에 꺼지면 다시 받지만 기록은 한 번만 남는다.
     * 해석할 수 없는 메시지만 DLT로 보낸다. 저장 실패는 DB 장애처럼 곧 풀릴 일이고, DLT로 보내면 회차가 취소됐는데 예약이 남으므로
     * 간격을 늘려 가며 계속 다시 시도한다. 그동안 이 파티션은 멈추며, 그 신호는 consumer lag으로 본다.
     */
    @Bean(CONTAINER_FACTORY)
    ConcurrentKafkaListenerContainerFactory<String, String> scheduleCanceledContainerFactory(
            ConsumerFactory<String, String> consumerFactory, KafkaTemplate<String, String> kafkaTemplate) {
        ExponentialBackOff retryUntilRecovered = new ExponentialBackOff(RETRY_INITIAL_MS, 2);
        retryUntilRecovered.setMaxInterval(RETRY_MAX_MS);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                new DeadLetterPublishingRecoverer(
                        kafkaTemplate, (record, ex) -> new TopicPartition(DEAD_LETTER_TOPIC, -1)),
                retryUntilRecovered);
        errorHandler.addNotRetryableExceptions(InvalidScheduleCanceledMessage.class);

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        factory.getContainerProperties().setAckMode(AckMode.RECORD);
        return factory;
    }
}
