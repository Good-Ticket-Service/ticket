package com.sparta.ticket.reservation.booking.application.port;

import java.time.LocalDateTime;

/**
 * 결제 준비 요청. totalPrice는 좌석 가격 스냅샷의 합계이고, 할인액은 booking이 정해 넘긴다. payment는 할인액을 고치지 않고
 * 검증만 하므로, 결제 금액(totalPrice - discountAmount)이 최소 결제 금액 아래로 내려가지 않게 하는 것도 booking 몫이다.
 * expiresAt은 선점 만료 시각이다. payment가 PG 승인 직전에 한 번 더 확인한다.
 */
public record PaymentPrepareRequest(
        String orderId,
        Long reservationId,
        Long userId,
        Long sellerId,
        Long performanceId,
        Long scheduleId,
        long totalPrice,
        long discountAmount,
        String pgProvider,
        LocalDateTime expiresAt) {}
