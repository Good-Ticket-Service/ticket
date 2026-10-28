package com.sparta.ticket.reservation.booking.infrastructure.payment;

/** GET /internal/payments/{orderId} 응답에서 booking이 쓰는 필드만 받는다. 나머지 필드는 무시한다. */
public record PaymentDetailResponse(
        String orderId, Long reservationId, String status, String failureType, String failureCode) {}
