package com.sparta.ticket.reservation.booking.application.port;

import java.time.LocalDateTime;

/**
 * 결제 준비 요청. baseAmount는 좌석 가격 스냅샷의 합계이고, 쿠폰은 payment가 적용한다.
 * expiresAt은 선점 만료 시각이다. payment가 PG 승인 직전에 한 번 더 확인한다.
 */
public record PaymentPrepareRequest(
        String orderId,
        Long reservationId,
        Long userId,
        long baseAmount,
        Long couponId,
        String pgProvider,
        LocalDateTime expiresAt) {}
