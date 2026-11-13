package com.sparta.ticket.payment.application.dto.payment;

import com.sparta.ticket.payment.domain.model.payment.Payment;
import com.sparta.ticket.payment.domain.model.payment.PaymentFailureType;
import com.sparta.ticket.payment.domain.model.payment.PaymentStatus;

public record ConfirmResult(Long paymentId, PaymentStatus status, PaymentFailureType failureType, String failureCode) {
    /** 메시지 대체 승인 응답(#422). 등록만 되고 claim 전이면 READY를 그대로 알린다. */
    public static ConfirmResult current(Payment payment) {
        return new ConfirmResult(
                payment.getId(),
                payment.getStatus(),
                PaymentFailureType.of(payment.getFailureCode()),
                payment.getFailureCode());
    }

    public static ConfirmResult from(Payment payment) {
        return new ConfirmResult(
                payment.getId(),
                payment.getStatus() == PaymentStatus.READY && payment.getMessageConfirmBefore() != null
                        ? PaymentStatus.IN_PROGRESS
                        : payment.getStatus(),
                PaymentFailureType.of(payment.getFailureCode()),
                payment.getFailureCode());
    }
}
