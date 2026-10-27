package com.sparta.ticket.payment.application.dto.payment;

import com.sparta.ticket.payment.domain.model.payment.Payment;
import com.sparta.ticket.payment.domain.model.payment.PaymentFailureType;
import com.sparta.ticket.payment.domain.model.payment.PaymentStatus;

/**
 * 결제 준비 결과. 같은 orderId의 재요청에는 저장된 결제에서 다시 만들므로 처음 응답과 같다. 카카오 준비가 실패하면 HTTP 200에
 * FAILED(READY_FAILED, failureType CLOSED)가 온다. 예매는 이를 준비 성공으로 보지 않는다.
 */
public record CheckoutResult(
        Long paymentId,
        String orderId,
        int amount,
        PaymentStatus status,
        PaymentFailureType failureType,
        String failureCode,
        String checkoutUrl) {

    public static CheckoutResult from(Payment payment) {
        return new CheckoutResult(
                payment.getId(),
                payment.getOrderId(),
                payment.getAmount(),
                payment.getStatus(),
                PaymentFailureType.of(payment.getFailureCode()),
                payment.getFailureCode(),
                payment.getCheckoutUrl());
    }
}
