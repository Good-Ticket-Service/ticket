package com.sparta.ticket.payment.application.dto.payment;

import com.sparta.ticket.payment.domain.model.payment.Payment;
import com.sparta.ticket.payment.domain.model.payment.PaymentFailureType;
import com.sparta.ticket.payment.domain.model.payment.PaymentStatus;
import com.sparta.ticket.payment.domain.model.refund.Refund;
import com.sparta.ticket.payment.domain.model.refund.RefundReason;
import com.sparta.ticket.payment.domain.model.refund.RefundStatus;
import java.time.LocalDateTime;

/** 결제 조회 결과. 예매가 승인 결과를 모를 때 이 값으로 다음 행동을 고른다(confirmRequestedAt으로 재전송 여부 판단). */
public record PaymentDetail(
        Long paymentId,
        String orderId,
        Long reservationId,
        PaymentStatus status,
        int amount,
        PaymentFailureType failureType,
        String failureCode,
        LocalDateTime confirmRequestedAt,
        LocalDateTime approvedAt,
        RefundDetail refund) {

    public static PaymentDetail of(Payment payment, Refund refund) {
        return new PaymentDetail(
                payment.getId(),
                payment.getOrderId(),
                payment.getReservationId(),
                payment.getStatus(),
                payment.getAmount(),
                PaymentFailureType.of(payment.getFailureCode()),
                payment.getFailureCode(),
                payment.getConfirmRequestedAt(),
                payment.getApprovedAt(),
                refund == null ? null : RefundDetail.from(refund));
    }

    public record RefundDetail(RefundStatus status, RefundReason reason, int refundAmount, LocalDateTime refundedAt) {

        static RefundDetail from(Refund refund) {
            return new RefundDetail(
                    refund.getStatus(), refund.getReason(), refund.getRefundAmount(), refund.getRefundedAt());
        }
    }
}
