package com.sparta.ticket.reservation.booking.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.sparta.ticket.reservation.booking.application.BookingMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;

class PaymentResultKafkaConfigTest {

    @SuppressWarnings("unchecked")
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(PaymentResultKafkaConfig.class)
            .withBean(ConsumerFactory.class, () -> mock(ConsumerFactory.class))
            .withBean(KafkaTemplate.class, () -> mock(KafkaTemplate.class))
            .withBean(BookingMetrics.class, () -> mock(BookingMetrics.class));

    @Test
    @DisplayName("결과 소비자는 설정한 수만큼의 스레드로 뜬다")
    void appliesConfiguredConcurrency() {
        runner.withPropertyValues("reservation.booking.payment-result.concurrency=3")
                .run(context -> {
                    ConcurrentKafkaListenerContainerFactory<?, ?> factory = context.getBean(
                            PaymentResultKafkaConfig.CONTAINER_FACTORY, ConcurrentKafkaListenerContainerFactory.class);
                    ConcurrentMessageListenerContainer<?, ?> container =
                            factory.createContainer(PaymentResultKafkaConfig.TOPIC);
                    assertThat(container.getConcurrency()).isEqualTo(3);
                });
    }

    @Test
    @DisplayName("동시 스레드 수가 설정에 없으면 조용히 1스레드로 뜨지 않고 기동이 실패한다")
    void failsWithoutConcurrency() {
        runner.run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("동시 스레드 수가 0이면 기동이 실패한다")
    void rejectsZeroConcurrency() {
        runner.withPropertyValues("reservation.booking.payment-result.concurrency=0")
                .run(context -> assertThat(context).hasFailed());
    }
}
