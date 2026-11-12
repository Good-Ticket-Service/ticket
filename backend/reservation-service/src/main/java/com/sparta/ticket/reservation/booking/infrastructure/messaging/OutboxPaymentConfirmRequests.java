package com.sparta.ticket.reservation.booking.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.reservation.booking.application.ChangeStamps;
import com.sparta.ticket.reservation.booking.application.port.PaymentConfirmRequests;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 승인 요청을 Outbox에 남긴다. 결제 중 전이와 같은 트랜잭션이어야 둘 중 하나만 남는 일이 없다.
 * 커밋되면 발행 쪽을 깨워 발행 스케줄러의 다음 차례를 기다리지 않게 한다. 롤백된 요청이 나가지 않도록 커밋 뒤에만 깨운다.
 * 깨우기를 놓쳐도 행은 남아 발행 스케줄러가 보낸다.
 */
@Component
@RequiredArgsConstructor
class OutboxPaymentConfirmRequests implements PaymentConfirmRequests {

    private final OutboxRowRepository outbox;
    private final ObjectMapper objectMapper;
    private final ChangeStamps changeStamps;
    private final ObjectProvider<OutboxWakeup> wakeup;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void request(PaymentConfirmRequest request) {
        PaymentConfirmRequestedMessage message = PaymentConfirmRequestedMessage.of(request, changeStamps.now());
        outbox.save(new OutboxRow(
                PaymentConfirmRequestedMessage.TOPIC, message.key(), message.toJson(objectMapper), changeStamps.now()));
        wakeup.ifAvailable(
                target -> TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        target.wake();
                    }
                }));
    }
}
