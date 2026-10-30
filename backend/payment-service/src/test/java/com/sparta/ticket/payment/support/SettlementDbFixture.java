package com.sparta.ticket.payment.support;

import com.sparta.ticket.payment.domain.model.settlement.SaleSource;
import com.sparta.ticket.payment.domain.model.settlement.SettlementItem;
import com.sparta.ticket.payment.domain.model.settlement.SettlementRules;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;

/** 정산 통합 시험용 결제·환불 행과 SALE 항목. 정산 코드가 결제 Entity를 보지 않으므로 SQL로 원천 행만 만든다. */
public final class SettlementDbFixture {

    public static final LocalDateTime APPROVED = LocalDateTime.of(2026, 11, 2, 10, 0);
    private static final AtomicLong RESERVATION_IDS = new AtomicLong(8_000_000);

    private SettlementDbFixture() {}

    /** 판매자 sellerId의 DONE 결제. 정가 gross, 할인 0. */
    public static long payment(JdbcTemplate jdbc, long sellerId, int gross) {
        return jdbc.queryForObject(
                "INSERT INTO p_payments (order_id, reservation_id, user_id, seller_id, performance_id, schedule_id,"
                        + " pg_provider, expires_at, total_price, discount_amount, amount, status, created_at, created_by,"
                        + " updated_at, updated_by) VALUES (?, ?, 1, ?, 3, 4, 'TOSS', ?, ?, 0, ?, 'DONE', now(), 't',"
                        + " now(), 't') RETURNING id",
                Long.class,
                "ledger-" + UUID.randomUUID(),
                RESERVATION_IDS.incrementAndGet(),
                sellerId,
                APPROVED.plusMinutes(10),
                gross,
                gross);
    }

    public static long refund(JdbcTemplate jdbc, long paymentId, int amount) {
        return jdbc.queryForObject(
                "INSERT INTO p_refunds (payment_id, reservation_id, refund_amount, reason, status, created_at,"
                        + " created_by, updated_at, updated_by) VALUES (?, ?, ?, 'USER_CANCEL', 'DONE', now(), 't',"
                        + " now(), 't') RETURNING id",
                Long.class,
                paymentId,
                RESERVATION_IDS.incrementAndGet(),
                amount);
    }

    /** 저장하지 않은 posted SALE. 금액은 설정의 승인 시각 정책으로 계산한다. */
    public static SettlementItem sale(SettlementRules rules, long paymentId, long sellerId, int gross) {
        return rules.saleFor(new SaleSource(
                paymentId, sellerId, 3L, 4L, LocalDateTime.of(2026, 11, 4, 19, 0), APPROVED, gross, 0, 0, gross));
    }
}
