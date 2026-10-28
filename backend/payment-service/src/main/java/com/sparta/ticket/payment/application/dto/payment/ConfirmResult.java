package com.sparta.ticket.payment.application.dto.payment;

import com.sparta.ticket.payment.domain.model.payment.Payment;
import com.sparta.ticket.payment.domain.model.payment.PaymentFailureType;
import com.sparta.ticket.payment.domain.model.payment.PaymentStatus;

public record ConfirmResult(Long paymentId, PaymentStatus status, PaymentFailureType failureType, String failureCode) {
    public static ConfirmResult from(Payment payment) {
        return new ConfirmResult(
                payment.getId(),
                payment.getStatus(),
                PaymentFailureType.of(payment.getFailureCode()),
                payment.getFailureCode());
    }
}
