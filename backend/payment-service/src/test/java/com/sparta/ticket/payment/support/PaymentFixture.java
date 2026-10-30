package com.sparta.ticket.payment.support;

import com.sparta.ticket.payment.domain.model.payment.PaymentOrder;
import com.sparta.ticket.payment.domain.model.pg.PgProvider;
import java.time.LocalDateTime;

public final class PaymentFixture {

    public static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 20, 0);
    public static final Long USER_ID = 1L;
    public static final Long SELLER_ID = 2L;
    /** 회차 시작 시각. 정산 가능일이 영업일 달력 범위 안에 들도록 정한다. */
    public static final LocalDateTime SCHEDULE_START_AT = LocalDateTime.of(2026, 11, 7, 19, 0);

    private PaymentFixture() {}

    public static PaymentOrder order(String orderId, long reservationId) {
        return order(orderId, reservationId, NOW.plusMinutes(10));
    }

    public static PaymentOrder order(String orderId, long reservationId, LocalDateTime expiresAt) {
        return order(orderId, reservationId, 50_000, 5_000, expiresAt);
    }

    public static PaymentOrder order(String orderId, long reservationId, int totalPrice, int discountAmount) {
        return order(orderId, reservationId, totalPrice, discountAmount, NOW.plusMinutes(10));
    }

    public static PaymentOrder kakaoOrder(String orderId, long reservationId) {
        return withProvider(order(orderId, reservationId), PgProvider.KAKAO);
    }

    public static PaymentOrder withProvider(PaymentOrder order, PgProvider provider) {
        return new PaymentOrder(
                order.orderId(),
                order.reservationId(),
                order.userId(),
                order.sellerId(),
                order.performanceId(),
                order.scheduleId(),
                provider,
                order.totalPrice(),
                order.discountAmount(),
                order.expiresAt(),
                order.scheduleStartAt());
    }

    private static PaymentOrder order(
            String orderId, long reservationId, int totalPrice, int discountAmount, LocalDateTime expiresAt) {
        return new PaymentOrder(
                orderId,
                reservationId,
                USER_ID,
                SELLER_ID,
                10L,
                20L,
                PgProvider.TOSS,
                totalPrice,
                discountAmount,
                expiresAt,
                SCHEDULE_START_AT);
    }
}
