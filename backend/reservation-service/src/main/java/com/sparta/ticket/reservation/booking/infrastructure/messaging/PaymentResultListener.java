package com.sparta.ticket.reservation.booking.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.reservation.booking.application.payment.PaymentResultReceiver;
import com.sparta.ticket.reservation.booking.infrastructure.persistence.CompletionPool;
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
    /** 결과 반영은 전용 DB 연결 풀로 한다. 사용자 요청이 기본 풀을 다 써도 확정이 멈추지 않게 하기 위해서다. */
    void onMessage(String payload) {
        CompletionPool.run(() -> receiver.receive(PaymentResultMessage.parse(payload, objectMapper)));
    }
}
