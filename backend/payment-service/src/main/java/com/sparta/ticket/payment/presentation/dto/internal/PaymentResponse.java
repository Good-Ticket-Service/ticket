package com.sparta.ticket.payment.presentation.dto.internal;

import com.sparta.ticket.payment.application.dto.payment.PaymentDetail;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** GET /internal/payments/{orderId} 응답. 시각은 +09:00 오프셋을 붙인 ISO 8601이다. */
public record PaymentResponse(
        Long paymentId,
        String orderId,
        Long reservationId,
        String status,
        int amount,
        String failureType,
        String failureCode,
        OffsetDateTime confirmRequestedAt,
        OffsetDateTime approvedAt,
        RefundResponse refund) {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    public static PaymentResponse from(PaymentDetail detail) {
        return new PaymentResponse(
                detail.paymentId(),
                detail.orderId(),
                detail.reservationId(),
                detail.status().name(),
                detail.amount(),
                detail.failureType() == null ? null : detail.failureType().name(),
                detail.failureCode(),
                offset(detail.confirmRequestedAt()),
                offset(detail.approvedAt()),
                detail.refund() == null
                        ? null
                        : new RefundResponse(
                                detail.refund().status().name(),
                                detail.refund().reason().name(),
                                detail.refund().refundAmount(),
                                offset(detail.refund().refundedAt())));
    }

    private static OffsetDateTime offset(LocalDateTime time) {
        return time == null ? null : time.atZone(SEOUL).toOffsetDateTime();
    }

    public record RefundResponse(String status, String reason, int refundAmount, OffsetDateTime refundedAt) {}
}
