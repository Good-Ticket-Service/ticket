package com.sparta.ticket.payment.application.port.payment;

import com.sparta.ticket.payment.domain.model.payment.Payment;
import com.sparta.ticket.payment.domain.model.refund.Refund;

/**
 * 결제·환불 확정의 전달 포트. 최초 승인 결과는 확정 전이와 같은 트랜잭션에서 Outbox에 쓴다.
 * 환불 전달은 별도 계약 전환 전까지 구현하지 않는다.
 */
public interface PaymentResultPublisher {

    /** 최초 DONE/FAILED 결과를 보존한다. 환불 후 CANCELED는 새 승인 결과를 만들지 않는다. */
    void paymentFinalized(Payment payment);

    /** 환불이 DONE, FAILED, SKIPPED로 확정됐을 때. */
    void refundFinalized(Payment payment, Refund refund);
}
