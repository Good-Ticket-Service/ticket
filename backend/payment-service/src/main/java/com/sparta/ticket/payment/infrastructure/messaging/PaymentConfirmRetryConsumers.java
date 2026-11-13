package com.sparta.ticket.payment.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.payment.application.service.payment.MessageApprovalProperties;
import com.sparta.ticket.payment.application.service.payment.MessageApprovalService;
import com.sparta.ticket.payment.domain.model.pg.PgProvider;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Locale;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * PG별 retry Topic 레인(#417 B). 결제 내부 Topic이며 예매와의 계약이 아니다. 원본 레인에서 PG별 자리·허가를 얻지 못한 레코드를
 * 같은 내용으로 받아, 다시 넘기지 않고 이 레인 안에서 재시도한다. 레인마다 Consumer Group·소유권·worker·미완료 상한이 따로라,
 * 느린 PG는 자기 레인만 막는다.
 */
public final class PaymentConfirmRetryConsumers {
    public static final String TOSS_TOPIC = "payment.confirm.retry.toss";
    public static final String KAKAO_TOPIC = "payment.confirm.retry.kakao";

    private PaymentConfirmRetryConsumers() {}

    public static String topic(PgProvider provider) {
        return "payment.confirm.retry." + provider.name().toLowerCase(Locale.ROOT);
    }

    /** retry 레인의 worker는 PG별 자리만큼 PG를 기다리고, 나머지는 등록·재시도 확인에 쓴다. */
    static int workers(MessageApprovalProperties properties) {
        return properties.getPerProvider() + Math.max(4, properties.getPerProvider() / 4);
    }

    @Component
    @ConditionalOnProperty(name = "payment.messaging.confirm.enabled", havingValue = "true")
    public static class Toss extends ConfirmLane {
        public static final String ID = "payment-confirm-retry-toss";

        public Toss(
                MessageApprovalService service,
                MessageApprovalProperties properties,
                @Qualifier("paymentConfirmRetryTossOwnership") PaymentConfirmOwnership ownership,
                KafkaListenerEndpointRegistry registry,
                KafkaTemplate<String, String> kafka,
                ObjectMapper mapper,
                MeterRegistry meters,
                Clock clock) {
            super(
                    "retry-toss",
                    ID,
                    false,
                    workers(properties),
                    service,
                    properties,
                    ownership,
                    registry,
                    kafka,
                    mapper,
                    meters,
                    clock);
        }

        @KafkaListener(
                id = ID,
                groupId = ID,
                topics = TOSS_TOPIC,
                containerFactory = "paymentConfirmRetryTossContainerFactory")
        public void receive(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
            handle(record, acknowledgment);
        }
    }

    @Component
    @ConditionalOnProperty(name = "payment.messaging.confirm.enabled", havingValue = "true")
    public static class Kakao extends ConfirmLane {
        public static final String ID = "payment-confirm-retry-kakao";

        public Kakao(
                MessageApprovalService service,
                MessageApprovalProperties properties,
                @Qualifier("paymentConfirmRetryKakaoOwnership") PaymentConfirmOwnership ownership,
                KafkaListenerEndpointRegistry registry,
                KafkaTemplate<String, String> kafka,
                ObjectMapper mapper,
                MeterRegistry meters,
                Clock clock) {
            super(
                    "retry-kakao",
                    ID,
                    false,
                    workers(properties),
                    service,
                    properties,
                    ownership,
                    registry,
                    kafka,
                    mapper,
                    meters,
                    clock);
        }

        @KafkaListener(
                id = ID,
                groupId = ID,
                topics = KAKAO_TOPIC,
                containerFactory = "paymentConfirmRetryKakaoContainerFactory")
        public void receive(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
            handle(record, acknowledgment);
        }
    }
}
