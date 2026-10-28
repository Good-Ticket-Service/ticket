package com.sparta.ticket.payment.infrastructure.payment;

import com.sparta.ticket.payment.application.port.payment.PaymentResultPublisher;
import com.sparta.ticket.payment.domain.model.payment.Payment;
import com.sparta.ticket.payment.domain.model.refund.Refund;
import org.springframework.stereotype.Component;

/** MVP는 동기 응답·조회로 결과를 전달한다. 후속 Outbox 구현은 같은 포트를 사용한다. */
@Component
public class NoopResultPublisher implements PaymentResultPublisher {
    @Override
    public void paymentFinalized(Payment payment) {}

    @Override
    public void refundFinalized(Payment payment, Refund refund) {}
}
