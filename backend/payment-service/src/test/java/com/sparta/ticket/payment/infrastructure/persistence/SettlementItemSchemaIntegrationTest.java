package com.sparta.ticket.payment.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.payment.domain.model.settlement.SaleSource;
import com.sparta.ticket.payment.domain.model.settlement.SettlementItem;
import com.sparta.ticket.payment.domain.model.settlement.SettlementItemStatus;
import com.sparta.ticket.payment.domain.model.settlement.SettlementRules;
import com.sparta.ticket.payment.domain.repository.settlement.SettlementItemRepository;
import com.sparta.ticket.payment.support.PaymentIntegrationTest;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** p_settlement_items의 부분 UNIQUE·CHECK가 실제 PostgreSQL에서 동작하는지 본다(Task 20-1). 결제 경로와는 아직 연결하지 않는다. */
class SettlementItemSchemaIntegrationTest extends PaymentIntegrationTest {

    private static final AtomicLong RESERVATION_IDS = new AtomicLong(7_000_000);
    private static final LocalDateTime APPROVED = LocalDateTime.of(2026, 11, 2, 10, 0);

    @Autowired
    private SettlementItemRepository items;

    @Autowired
    private SettlementRules rules;

    @Autowired
    private JdbcTemplate jdbc;

    private long payment() {
        jdbc.update(
                "INSERT INTO p_payments (order_id, reservation_id, user_id, seller_id, performance_id, schedule_id,"
                        + " pg_provider, expires_at, total_price, discount_amount, amount, status, created_at, created_by,"
                        + " updated_at, updated_by) VALUES (?, ?, 1, 2, 3, 4, 'TOSS', ?, 10000, 0, 10000, 'DONE', now(), 't',"
                        + " now(), 't')",
                "settle-" + UUID.randomUUID(),
                RESERVATION_IDS.incrementAndGet(),
                APPROVED.plusMinutes(10));
        return jdbc.queryForObject("SELECT max(id) FROM p_payments", Long.class);
    }

    private SettlementItem sale(long paymentId, int paid) {
        return rules.saleFor(new SaleSource(
                paymentId, 2L, 3L, 4L, LocalDateTime.of(2026, 11, 4, 19, 0), APPROVED, 10_000, 0, 0, paid));
    }

    @Test
    void savesPostedAndUnpostedSaleOncePerPayment() {
        long paymentId = payment();
        SettlementItem saved = items.saveAndFlush(sale(paymentId, 10_000));

        assertThat(items.findSaleByPaymentId(paymentId))
                .get()
                .extracting(SettlementItem::getId)
                .isEqualTo(saved.getId());
        assertThat(saved.getStatus()).isEqualTo(SettlementItemStatus.PENDING);
        assertThat(saved.getPolicyVersion()).isEqualTo("2026-10-30-v1");
        assertThatThrownBy(() -> items.saveAndFlush(sale(paymentId, 10_000)))
                .isInstanceOf(DataIntegrityViolationException.class);

        SettlementItem unposted = items.saveAndFlush(sale(payment(), 9_000));
        assertThat(unposted.getStatus()).isEqualTo(SettlementItemStatus.UNPOSTED);
    }

    @Test
    void checksRejectBrokenConservationAndDisguisedUnposted() {
        long posted = items.saveAndFlush(sale(payment(), 10_000)).getId();
        long unposted = items.saveAndFlush(sale(payment(), 9_000)).getId();

        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE p_settlement_items SET seller_amount = seller_amount + 1 WHERE id = ?", posted))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE p_settlement_items SET commission = 0 WHERE id = ?", unposted))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () -> jdbc.update("UPDATE p_settlement_items SET status = 'ON_STATEMENT' WHERE id = ?", posted))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deductionIsOnePerRefundAndCannotOverdraw() {
        long paymentId = payment();
        long saleId = items.saveAndFlush(sale(paymentId, 10_000)).getId();
        jdbc.update(
                "INSERT INTO p_refunds (payment_id, reservation_id, refund_amount, reason, status, created_at, created_by,"
                        + " updated_at, updated_by) VALUES (?, 1, 10000, 'SCHEDULE_CANCELED', 'DONE', now(), 't', now(), 't')",
                paymentId);
        long refundId = jdbc.queryForObject("SELECT id FROM p_refunds WHERE payment_id = ?", Long.class, paymentId);
        jdbc.update("UPDATE p_settlement_items SET refund_id = ? WHERE id = ?", refundId, saleId);
        String insert =
                "INSERT INTO p_settlement_items (kind, status, seller_id, payment_id, refund_id, origin_item_id,"
                        + " principal, remaining, created_at, created_by, updated_at, updated_by)"
                        + " VALUES ('DEDUCTION', ?, 2, ?, ?, ?, 8680, ?, now(), 't', now(), 't')";

        jdbc.update(insert, "OPEN", paymentId, refundId, saleId, 8680);

        assertThatThrownBy(() -> jdbc.update(insert, "OPEN", paymentId, refundId, saleId, 8680))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE p_settlement_items SET remaining = 8681 WHERE kind = 'DEDUCTION' AND refund_id = ?",
                        refundId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE p_settlement_items SET remaining = 0 WHERE kind = 'DEDUCTION' AND refund_id = ?",
                        refundId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
