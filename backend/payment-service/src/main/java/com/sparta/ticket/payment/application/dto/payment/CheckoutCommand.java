package com.sparta.ticket.payment.application.dto.payment;

import com.sparta.ticket.payment.domain.model.payment.PaymentOrder;
import com.sparta.ticket.payment.domain.model.pg.PgProvider;
import java.time.LocalDateTime;

/**
 * 결제 준비 요청. 예매가 확정한 정가·할인액을 그대로 받는다. 결제는 쿠폰을 모르고 금액만 검증한다.
 * scheduleStartAt은 선택 값이다(#160). 없으면 결제는 그대로 진행하고 정산만 보류된다.
 */
public record CheckoutCommand(
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
        LocalDateTime scheduleStartAt) {

    /** 회차 시작 시각 없이 온 준비 요청. */
    public CheckoutCommand(
            String orderId,
            Long reservationId,
            Long userId,
            Long sellerId,
            Long performanceId,
            Long scheduleId,
            PgProvider pgProvider,
            int totalPrice,
            int discountAmount,
            LocalDateTime expiresAt) {
        this(
                orderId,
                reservationId,
                userId,
                sellerId,
                performanceId,
                scheduleId,
                pgProvider,
                totalPrice,
                discountAmount,
                expiresAt,
                null);
    }

    public PaymentOrder toOrder() {
        return new PaymentOrder(
                orderId,
                reservationId,
                userId,
                sellerId,
                performanceId,
                scheduleId,
                pgProvider,
                totalPrice,
                discountAmount,
                expiresAt,
                scheduleStartAt);
    }
}
