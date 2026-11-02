package com.sparta.ticket.reservation.booking.infrastructure.messaging;

import com.sparta.ticket.reservation.booking.application.BookingMetrics;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Slf4j
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
     * 간격을 늘려 가며 계속 다시 시도한다. 그동안 이 파티션은 멈추며, 그 신호는 처리 실패 지표와 consumer lag으로 본다.
     */
    @Bean(CONTAINER_FACTORY)
    ConcurrentKafkaListenerContainerFactory<String, String> scheduleCanceledContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            KafkaTemplate<String, String> kafkaTemplate,
            BookingMetrics metrics) {
        ExponentialBackOff retryUntilRecovered = new ExponentialBackOff(RETRY_INITIAL_MS, 2);
        retryUntilRecovered.setMaxInterval(RETRY_MAX_MS);

        DeadLetterPublishingRecoverer deadLetters = new DeadLetterPublishingRecoverer(
                kafkaTemplate, (record, ex) -> new TopicPartition(DEAD_LETTER_TOPIC, -1));
        ConsumerAwareRecordRecoverer countedDeadLetters = (record, consumer, ex) -> {
            deadLetters.accept(record, consumer, ex);
            metrics.scheduleCancelDeadLetter();
            log.error(
                    "회차 취소 메시지를 DLT로 옮겼다. 그 회차의 예약 정리가 시작되지 않으므로 관리자가 확인한다. key={}, partition={}, offset={}",
                    record.key(),
                    record.partition(),
                    record.offset(),
                    ex);
        };
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(countedDeadLetters, retryUntilRecovered);
        errorHandler.addNotRetryableExceptions(InvalidScheduleCanceledMessage.class);
        errorHandler.setRetryListeners((record, ex, attempt) -> {
            metrics.scheduleCancelDeliveryFailed();
            log.warn(
                    "회차 취소 메시지 처리에 실패했다. key={}, partition={}, offset={}, attempt={}",
                    record.key(),
                    record.partition(),
                    record.offset(),
                    attempt,
                    ex);
        });

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        factory.getContainerProperties().setAckMode(AckMode.RECORD);
        return factory;
    }
}
