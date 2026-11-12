package com.sparta.ticket.reservation.booking.infrastructure.messaging;

import com.sparta.ticket.reservation.booking.application.BookingMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

@Slf4j
@Configuration
@EnableConfigurationProperties(PaymentResultProperties.class)
class PaymentResultKafkaConfig {

    static final String TOPIC = "payment.result";
    // Note: 회차 취소와 그룹을 나눈다. 한 그룹에 구독 토픽이 다른 컨테이너가 둘 있으면 한쪽이 켜지고 꺼질 때마다 다른 쪽도 리밸런스된다
    static final String GROUP_ID = "reservation-booking-payment-result";
    static final String CONTAINER_FACTORY = "paymentResultContainerFactory";

    private static final String DEAD_LETTER_TOPIC = TOPIC + ".DLT";

    /**
     * 해석할 수 없는 메시지만 DLT로 보낸다. 그 결제의 결과는 결과 확인 스케줄러가 payment에 물어 반영하므로 잃는 것은 빠른 전달뿐이다.
     * 반영 실패는 계속 다시 시도한다.
     */
    @Bean(CONTAINER_FACTORY)
    ConcurrentKafkaListenerContainerFactory<String, String> paymentResultContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            KafkaTemplate<String, String> kafkaTemplate,
            BookingMetrics metrics,
            PaymentResultProperties properties) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = RetryingListenerContainers.create(
                consumerFactory,
                kafkaTemplate,
                DEAD_LETTER_TOPIC,
                InvalidPaymentResultMessage.class,
                (record, ex) -> {
                    metrics.paymentResultDeadLetter();
                    log.error(
                            "결제 결과 메시지를 DLT로 옮겼다. 그 결제는 결과 확인 스케줄러가 payment에 물어 끝낸다. key={}, partition={}, offset={}",
                            record.key(),
                            record.partition(),
                            record.offset(),
                            ex);
                },
                (record, ex, attempt) -> {
                    metrics.paymentResultDeliveryFailed();
                    log.warn(
                            "결제 결과 메시지 처리에 실패했다. key={}, partition={}, offset={}, attempt={}",
                            record.key(),
                            record.partition(),
                            record.offset(),
                            attempt,
                            ex);
                });
        factory.setConcurrency(properties.concurrency());
        return factory;
    }
}
