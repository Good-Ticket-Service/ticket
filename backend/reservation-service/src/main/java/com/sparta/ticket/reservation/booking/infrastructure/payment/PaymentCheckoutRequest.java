package com.sparta.ticket.reservation.booking.infrastructure.payment;

import com.sparta.ticket.reservation.booking.application.port.PaymentPrepareRequest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** POST /internal/payments 요청. 금액은 원 단위 정수다. 시각은 오프셋을 붙여 보내 payment가 같은 순간으로 읽게 한다. */
public record PaymentCheckoutRequest(
        String orderId,
        Long reservationId,
        Long userId,
        Long sellerId,
        Long performanceId,
        Long scheduleId,
        int totalPrice,
        int discountAmount,
        String pgProvider,
        OffsetDateTime expiresAt) {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    static PaymentCheckoutRequest from(PaymentPrepareRequest request) {
        return new PaymentCheckoutRequest(
                request.orderId(),
                request.reservationId(),
                request.userId(),
                request.sellerId(),
                request.performanceId(),
                request.scheduleId(),
                Math.toIntExact(request.totalPrice()),
                Math.toIntExact(request.discountAmount()),
                request.pgProvider(),
                request.expiresAt().atOffset(KST));
    }
}
