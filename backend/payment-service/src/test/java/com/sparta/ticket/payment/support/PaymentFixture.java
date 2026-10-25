package com.sparta.ticket.payment.support;

import com.sparta.ticket.payment.domain.model.payment.PaymentOrder;
import com.sparta.ticket.payment.domain.model.pg.PgProvider;
import java.time.LocalDateTime;
import java.util.UUID;

public final class PaymentFixture {

    public static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 20, 0);
    public static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    public static final UUID SELLER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private PaymentFixture() {}

    public static PaymentOrder order(String orderId, long reservationId) {
        return new PaymentOrder(
                orderId, reservationId, USER_ID, SELLER_ID, 10L, 20L, PgProvider.TOSS, 50_000, 7L, NOW.plusMinutes(10));
    }
}
