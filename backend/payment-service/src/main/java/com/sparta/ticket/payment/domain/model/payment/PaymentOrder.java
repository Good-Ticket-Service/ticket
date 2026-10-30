package com.sparta.ticket.payment.domain.model.payment;

import com.sparta.ticket.payment.domain.model.pg.PgProvider;
import java.time.LocalDateTime;

/**
 * 준비 요청이 결제에 고정하는 내용. 같은 orderId로 다시 온 요청이 처음과 같은지 이 값으로 비교한다.
 * reservation·user·seller·performance·schedule은 다른 서비스의 ID라 값만 가진다.
 *
 * @param scheduleStartAt 회차 시작 시각(KST). 판매자 정산 가능일의 기준이며, 보내지 않은 요청은 없다(정산 보류)
 */
public record PaymentOrder(
        String orderId,
        Long reservationId,
        Long userId,
        Long sellerId,
        Long performanceId,
        Long scheduleId,
        PgProvider pgProvider,
        int totalPrice,
        int discountAmount,
        LocalDateTime expiresAt,
        LocalDateTime scheduleStartAt) {}
