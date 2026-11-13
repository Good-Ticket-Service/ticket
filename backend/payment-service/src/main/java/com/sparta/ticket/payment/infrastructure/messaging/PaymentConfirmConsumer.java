package com.sparta.ticket.payment.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.payment.application.service.payment.MessageApprovalProperties;
import com.sparta.ticket.payment.application.service.payment.MessageApprovalService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/** 예매가 보낸 승인 요청(원본 Topic) 레인. 자리·허가를 얻지 못한 레코드는 PG별 retry Topic으로 넘긴다(#417). */
@Component
@ConditionalOnProperty(name = "payment.messaging.confirm.enabled", havingValue = "true")
public class PaymentConfirmConsumer extends ConfirmLane {
    public static final String TOPIC = "payment.confirm.requested";
    public static final String ID = "payment-confirm";

    public PaymentConfirmConsumer(
            MessageApprovalService service,
            MessageApprovalProperties properties,
            @Qualifier("paymentConfirmOwnership") PaymentConfirmOwnership ownership,
            KafkaListenerEndpointRegistry registry,
            KafkaTemplate<String, String> kafka,
            ObjectMapper mapper,
            MeterRegistry meters,
            Clock clock) {
        super(
                "main",
                ID,
                properties.isForwardOnUnavailable(),
                properties.getWorkers(),
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
            groupId = "payment-confirm-requested",
            topics = TOPIC,
            containerFactory = "paymentConfirmContainerFactory")
    public void receive(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        handle(record, acknowledgment);
    }
}
