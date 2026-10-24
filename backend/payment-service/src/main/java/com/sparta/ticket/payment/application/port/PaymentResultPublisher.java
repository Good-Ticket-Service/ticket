package com.sparta.ticket.payment.application.port;

import com.sparta.ticket.payment.domain.Payment;
import com.sparta.ticket.payment.domain.Refund;

/**
 * 결제·환불 확정을 다른 서비스에 알리는 포트. 1차(Feign)는 HTTP 응답이 결과라 아무것도 하지 않고,
 * Kafka 전환 후에는 확정 전이와 같은 트랜잭션에서 Outbox에 쓴다.
 */
public interface PaymentResultPublisher {

    /** DONE, FAILED, CANCELED로 확정됐을 때. */
    void paymentFinalized(Payment payment);

    /** 환불이 DONE, FAILED, SKIPPED로 확정됐을 때. */
    void refundFinalized(Payment payment, Refund refund);
}
