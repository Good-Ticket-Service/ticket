package com.sparta.ticket.payment.domain.model.payment;

import com.sparta.ticket.payment.domain.model.pg.PgProvider;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 준비 요청이 결제에 고정하는 내용. 같은 orderId로 다시 온 요청이 처음과 같은지 이 값으로 비교한다.
 * reservation·user·seller·performance·schedule은 다른 서비스의 ID라 값만 가진다.
 */
public record PaymentOrder(
        String orderId,
        Long reservationId,
        UUID userId,
        UUID sellerId,
        Long performanceId,
        Long scheduleId,
        PgProvider pgProvider,
        int totalPrice,
        Long userCouponId,
        LocalDateTime expiresAt) {}
