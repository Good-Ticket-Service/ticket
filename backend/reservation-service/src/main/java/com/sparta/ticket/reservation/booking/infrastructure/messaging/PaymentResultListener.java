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
            // 동시 소비 스레드 수. DB가 포화하면 반영 한 건이 느려져 스레드 하나로는 결과가 밀린다. 파티션 수(12)를 넘지 않게 둔다
            concurrency = "${reservation.booking.payment-result.concurrency:1}",
            properties = "auto.offset.reset=earliest")
    /** 결과 반영은 전용 DB 연결 풀로 한다. 사용자 요청이 기본 풀을 다 써도 확정이 멈추지 않게 하기 위해서다. */
    void onMessage(String payload) {
        CompletionPool.run(() -> receiver.receive(PaymentResultMessage.parse(payload, objectMapper)));
    }
}
