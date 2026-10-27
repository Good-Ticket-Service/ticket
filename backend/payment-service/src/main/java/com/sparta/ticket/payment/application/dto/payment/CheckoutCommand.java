package com.sparta.ticket.payment.application.dto.payment;

import com.sparta.ticket.payment.domain.model.payment.PaymentOrder;
import com.sparta.ticket.payment.domain.model.pg.PgProvider;
import java.time.LocalDateTime;

/** 결제 준비 요청. 예매가 확정한 정가·할인액을 그대로 받는다. 결제는 쿠폰을 모르고 금액만 검증한다. */
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
        LocalDateTime expiresAt) {

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
                expiresAt);
    }
}
