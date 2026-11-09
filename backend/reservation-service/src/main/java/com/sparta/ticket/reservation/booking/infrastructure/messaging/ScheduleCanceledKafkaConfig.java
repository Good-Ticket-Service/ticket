package com.sparta.ticket.reservation.booking.infrastructure.messaging;

import com.sparta.ticket.reservation.booking.application.BookingMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

@Slf4j
@Configuration
class ScheduleCanceledKafkaConfig {

    static final String TOPIC = "schedule.canceled";
    static final String GROUP_ID = "reservation-booking";
    static final String CONTAINER_FACTORY = "scheduleCanceledContainerFactory";

    private static final String DEAD_LETTER_TOPIC = TOPIC + ".DLT";

    /** 저장 실패를 DLT로 보내면 회차가 취소됐는데 예약이 남으므로, 해석할 수 없는 메시지만 보내고 나머지는 계속 다시 시도한다. */
    @Bean(CONTAINER_FACTORY)
    ConcurrentKafkaListenerContainerFactory<String, String> scheduleCanceledContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            KafkaTemplate<String, String> kafkaTemplate,
            BookingMetrics metrics) {
        return RetryingListenerContainers.create(
                consumerFactory,
                kafkaTemplate,
                DEAD_LETTER_TOPIC,
                InvalidScheduleCanceledMessage.class,
                (record, ex) -> {
                    metrics.scheduleCancelDeadLetter();
                    log.error(
                            "회차 취소 메시지를 DLT로 옮겼다. 그 회차의 예약 정리가 시작되지 않으므로 관리자가 확인한다. key={}, partition={}, offset={}",
                            record.key(),
                            record.partition(),
                            record.offset(),
                            ex);
                },
                (record, ex, attempt) -> {
                    metrics.scheduleCancelDeliveryFailed();
                    log.warn(
                            "회차 취소 메시지 처리에 실패했다. key={}, partition={}, offset={}, attempt={}",
                            record.key(),
                            record.partition(),
                            record.offset(),
                            attempt,
                            ex);
                });
    }
}
