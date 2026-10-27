package com.sparta.ticket.payment.support;

import com.sparta.ticket.payment.domain.model.payment.PaymentOrder;
import com.sparta.ticket.payment.domain.model.pg.PgProvider;
import java.time.LocalDateTime;

public final class PaymentFixture {

    public static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 20, 0);
    public static final Long USER_ID = 1L;
    public static final Long SELLER_ID = 2L;

    private PaymentFixture() {}

    public static PaymentOrder order(String orderId, long reservationId) {
        return order(orderId, reservationId, NOW.plusMinutes(10));
    }

    public static PaymentOrder order(String orderId, long reservationId, LocalDateTime expiresAt) {
        return new PaymentOrder(
                orderId, reservationId, USER_ID, SELLER_ID, 10L, 20L, PgProvider.TOSS, 50_000, 7L, expiresAt);
    }
}
