package com.sparta.ticket.payment.infrastructure.messaging;

import com.sparta.ticket.payment.domain.model.payment.Payment;
import com.sparta.ticket.payment.domain.model.payment.PaymentFailureType;
import com.sparta.ticket.payment.domain.model.payment.PaymentStatus;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** #319의 최초 승인 결과. 환불 뒤의 현재 상태로 재구성하지 않고 Outbox에 고정한다. */
public record PaymentResultMessage(
        String eventId,
        int schemaVersion,
        OffsetDateTime occurredAt,
        String orderId,
        long reservationId,
        long paymentId,
        String status,
        int amount,
        PaymentFailureType failureType,
        String failureCode,
        OffsetDateTime approvedAt) {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    public static PaymentResultMessage from(Payment payment, OffsetDateTime occurredAt) {
        if (payment.getStatus() != PaymentStatus.DONE && payment.getStatus() != PaymentStatus.FAILED) {
            throw new IllegalArgumentException("최초 결제 결과가 확정되지 않았다");
        }
        boolean failed = payment.getStatus() == PaymentStatus.FAILED;
        return new PaymentResultMessage(
                UUID.randomUUID().toString(),
                1,
                occurredAt
                        .withOffsetSameInstant(SEOUL.getRules().getOffset(occurredAt.toInstant()))
                        .truncatedTo(ChronoUnit.MICROS),
                payment.getOrderId(),
                payment.getReservationId(),
                payment.getId(),
                payment.getStatus().name(),
                payment.getAmount(),
                failed ? PaymentFailureType.of(payment.getFailureCode()) : null,
                failed ? payment.getFailureCode() : null,
                failed ? null : atSeoul(payment.getApprovedAt()));
    }

    /** 재호출의 새 eventId·발생 시각은 비교하지 않고 최초 업무 의미만 확인한다. */
    public String meaning() {
        return paymentId + "|" + orderId + "|" + reservationId + "|" + status + "|" + amount + "|" + failureType + "|"
                + failureCode + "|" + approvedAt;
    }

    private static OffsetDateTime atSeoul(LocalDateTime time) {
        return time == null
                ? null
                : time.truncatedTo(ChronoUnit.MICROS).atZone(SEOUL).toOffsetDateTime();
    }
}
