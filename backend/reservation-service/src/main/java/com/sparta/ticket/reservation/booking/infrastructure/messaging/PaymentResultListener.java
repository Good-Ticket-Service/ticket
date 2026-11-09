package com.sparta.ticket.reservation.booking.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.reservation.booking.application.payment.PaymentResultReceiver;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class PaymentResultListener {

    private final PaymentResultReceiver receiver;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = PaymentResultKafkaConfig.TOPIC,
            groupId = PaymentResultKafkaConfig.GROUP_ID,
            containerFactory = PaymentResultKafkaConfig.CONTAINER_FACTORY,
            properties = "auto.offset.reset=earliest")
    void onMessage(String payload) {
        receiver.receive(PaymentResultMessage.parse(payload, objectMapper));
    }
}
