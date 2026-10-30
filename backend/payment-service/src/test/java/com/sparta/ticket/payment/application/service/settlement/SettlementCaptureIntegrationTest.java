package com.sparta.ticket.payment.application.service.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.payment.application.dto.payment.CheckoutCommand;
import com.sparta.ticket.payment.application.dto.payment.ConfirmCommand;
import com.sparta.ticket.payment.application.service.payment.PaymentService;
import com.sparta.ticket.payment.application.service.payment.PaymentTransactions;
import com.sparta.ticket.payment.application.service.refund.RefundTransactions;
import com.sparta.ticket.payment.domain.model.payment.Payment;
import com.sparta.ticket.payment.domain.model.payment.PaymentStatus;
import com.sparta.ticket.payment.domain.model.pg.PgProvider;
import com.sparta.ticket.payment.domain.model.pg.PgResult;
import com.sparta.ticket.payment.domain.model.refund.RefundReason;
import com.sparta.ticket.payment.domain.model.settlement.SettlementFailure;
import com.sparta.ticket.payment.domain.model.settlement.SettlementHoldReason;
import com.sparta.ticket.payment.domain.model.settlement.SettlementItem;
import com.sparta.ticket.payment.domain.model.settlement.SettlementItemStatus;
import com.sparta.ticket.payment.domain.repository.payment.PaymentRepository;
import com.sparta.ticket.payment.domain.repository.refund.RefundRepository;
import com.sparta.ticket.payment.domain.repository.settlement.SettlementItemRepository;
import com.sparta.ticket.payment.support.PaymentIntegrationTest;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 결제 승인 DONE에서 정산 항목·판매 분개를 포착한다(Task 20-3, settlement-design 4.2절, 시험 S2·S3·S5). */
class SettlementCaptureIntegrationTest extends PaymentIntegrationTest {

    private static final AtomicLong RESERVATION_IDS = new AtomicLong(9_000_000);
    private static final LocalDateTime SCHEDULE_START_AT = LocalDateTime.of(2026, 11, 7, 19, 0);
    /** 원장 INSERT 실패를 주입할 판매자. 다른 시험 데이터와 겹치지 않게 따로 둔다. */
    private static final long FAILING_SELLER_ID = 90_203L;

    @Autowired
    private PaymentService service;

    @Autowired
    private PaymentTransactions transactions;

    @Autowired
    private RefundTransactions refundTransactions;

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private RefundRepository refunds;

    @Autowired
    private SettlementItemRepository items;

    @Autowired
    private JdbcTemplate jdbc;

    /** 승인 요청까지 마친 IN_PROGRESS 결제. 정가 50,000·할인 5,000. */
    private Payment inProgress(long sellerId, LocalDateTime scheduleStartAt) {
        String orderId = "capture-" + UUID.randomUUID();
        service.checkout(new CheckoutCommand(
                orderId,
                RESERVATION_IDS.incrementAndGet(),
                1L,
                sellerId,
                10L,
                20L,
                PgProvider.TOSS,
                50_000,
                5_000,
                LocalDateTime.now(paymentClock).plusMinutes(10),
                scheduleStartAt));
        Payment payment = payments.findByOrderId(orderId).orElseThrow();
        transactions.requestConfirm(orderId, new ConfirmCommand("key-" + payment.getId(), 45_000));
        return transactions
                .markInProgress(payment.getId(), "key-" + payment.getId())
                .orElseThrow();
    }

    private void approve(Payment payment, int amount, LocalDateTime at) {
        transactions.complete(payment.getId(), new PgResult.Approved("key-" + payment.getId(), amount, at));
    }

    private Optional<SettlementItem> sale(Payment payment) {
        return items.findSaleByPaymentId(payment.getId());
    }

    private int ledgerTransactions(Payment payment) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM p_ledger_transactions WHERE payment_id = ?", Integer.class, payment.getId());
    }

    private PaymentStatus status(Payment payment) {
        return payments.findById(payment.getId()).orElseThrow().getStatus();
    }

    @Test
    void approvalCapturesPendingSaleAndSaleEntryOnce() {
        Payment payment = inProgress(2L, SCHEDULE_START_AT);

        approve(payment, 45_000, LocalDateTime.now(paymentClock));
        approve(payment, 45_000, LocalDateTime.now(paymentClock));

        SettlementItem sale = sale(payment).orElseThrow();
        assertThat(sale.getStatus()).isEqualTo(SettlementItemStatus.PENDING);
        assertThat(sale.getEligibleAt()).isNotNull();
        assertThat(sale.getScheduleStartAt()).isEqualTo(SCHEDULE_START_AT);
        assertThat(sale.amounts().sellerAmount()).isEqualTo(43_400);
        assertThat(ledgerTransactions(payment)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM p_ledger_entries e JOIN p_ledger_transactions t ON t.id = e.transaction_id"
                                + " WHERE t.payment_id = ?",
                        Integer.class,
                        payment.getId()))
                .as("할인 있음·예매수수료 없음: PG·할인 / 판매자·수수료·VAT")
                .isEqualTo(5);
    }

    @Test
    void missingScheduleStartHoldsSaleButKeepsEntry() {
        Payment payment = inProgress(2L, null);

        approve(payment, 45_000, LocalDateTime.now(paymentClock));

        SettlementItem sale = sale(payment).orElseThrow();
        assertThat(sale.getStatus()).isEqualTo(SettlementItemStatus.HELD);
        assertThat(sale.getHoldReason()).isEqualTo(SettlementHoldReason.MISSING_SCHEDULE_START);
        assertThat(ledgerTransactions(payment)).isEqualTo(1);
    }

    /** 정책 시작일(2026-01-01) 전 승인 시각으로 계산 실패를 만든다. 결제는 DONE이어야 한다. */
    @Test
    void calculationFailureKeepsPaymentDoneAndLeavesUnposted() {
        Payment payment = inProgress(2L, SCHEDULE_START_AT);

        approve(payment, 45_000, LocalDateTime.of(2025, 12, 31, 23, 0));

        assertThat(status(payment)).isEqualTo(PaymentStatus.DONE);
        SettlementItem sale = sale(payment).orElseThrow();
        assertThat(sale.getStatus()).isEqualTo(SettlementItemStatus.UNPOSTED);
        assertThat(sale.getFailureReason()).isEqualTo(SettlementFailure.POLICY_NOT_FOUND);
        assertThat(ledgerTransactions(payment)).isZero();
    }

    @Test
    void refundAcceptedWhileApprovingHoldsSaleFromStart() {
        Payment payment = inProgress(2L, SCHEDULE_START_AT);
        refundTransactions.accept(payment.getOrderId(), RefundReason.USER_CANCEL);

        approve(payment, 45_000, LocalDateTime.now(paymentClock));

        Long refundId = refunds.findByPaymentId(payment.getId()).orElseThrow().getId();
        SettlementItem sale = sale(payment).orElseThrow();
        assertThat(sale.getStatus()).isEqualTo(SettlementItemStatus.HELD);
        assertThat(sale.getHoldReason()).isEqualTo(SettlementHoldReason.REFUND_IN_PROGRESS);
        assertThat(sale.getRefundId()).isEqualTo(refundId);
        assertThat(ledgerTransactions(payment)).isEqualTo(1);
    }

    @Test
    void amountMismatchCreatesNoSale() {
        Payment payment = inProgress(2L, SCHEDULE_START_AT);

        approve(payment, 40_000, LocalDateTime.now(paymentClock));

        assertThat(status(payment)).isEqualTo(PaymentStatus.FAILED);
        assertThat(sale(payment)).isEmpty();
        assertThat(ledgerTransactions(payment)).isZero();
    }

    /** 원장 INSERT에서 DB 오류가 나면 결제 확정까지 함께 롤백되고, 같은 승인 결과로 다시 처리하면 한 번만 반영된다. */
    @Test
    void databaseErrorRollsBackApprovalAndRetryCapturesOnce() {
        Payment payment = inProgress(FAILING_SELLER_ID, SCHEDULE_START_AT);
        jdbc.execute("CREATE OR REPLACE FUNCTION fail_ledger_for_test() RETURNS trigger AS $$ BEGIN"
                + " IF NEW.seller_id = " + FAILING_SELLER_ID
                + " THEN RAISE EXCEPTION 'injected ledger failure'; END IF;"
                + " RETURN NEW; END $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER fail_ledger_for_test BEFORE INSERT ON p_ledger_transactions"
                + " FOR EACH ROW EXECUTE FUNCTION fail_ledger_for_test()");
        try {
            assertThatThrownBy(() -> approve(payment, 45_000, LocalDateTime.now(paymentClock)))
                    .isInstanceOf(RuntimeException.class);
            assertThat(status(payment)).isEqualTo(PaymentStatus.IN_PROGRESS);
            assertThat(sale(payment)).isEmpty();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS fail_ledger_for_test ON p_ledger_transactions");
            jdbc.execute("DROP FUNCTION IF EXISTS fail_ledger_for_test()");
        }

        approve(payment, 45_000, LocalDateTime.now(paymentClock));

        assertThat(status(payment)).isEqualTo(PaymentStatus.DONE);
        assertThat(sale(payment)).get().extracting(SettlementItem::getStatus).isEqualTo(SettlementItemStatus.PENDING);
        assertThat(ledgerTransactions(payment)).isEqualTo(1);
    }
}
