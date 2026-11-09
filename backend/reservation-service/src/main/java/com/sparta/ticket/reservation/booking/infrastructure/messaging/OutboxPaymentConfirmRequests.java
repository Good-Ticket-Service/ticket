package com.sparta.ticket.reservation.booking.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.reservation.booking.application.ChangeStamps;
import com.sparta.ticket.reservation.booking.application.port.PaymentConfirmRequests;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 승인 요청을 Outbox에 남긴다. 결제 중 전이와 같은 트랜잭션이어야 둘 중 하나만 남는 일이 없다. */
@Component
@RequiredArgsConstructor
class OutboxPaymentConfirmRequests implements PaymentConfirmRequests {

    private final OutboxRowRepository outbox;
    private final ObjectMapper objectMapper;
    private final ChangeStamps changeStamps;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void request(PaymentConfirmRequest request) {
        PaymentConfirmRequestedMessage message = PaymentConfirmRequestedMessage.of(request, changeStamps.now());
        outbox.save(new OutboxRow(
                PaymentConfirmRequestedMessage.TOPIC, message.key(), message.toJson(objectMapper), changeStamps.now()));
    }
}
