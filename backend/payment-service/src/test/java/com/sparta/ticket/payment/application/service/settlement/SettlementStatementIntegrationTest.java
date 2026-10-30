package com.sparta.ticket.payment.application.service.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.payment.application.service.payment.PaymentService;
import com.sparta.ticket.payment.application.service.payment.PaymentTransactions;
import com.sparta.ticket.payment.application.service.refund.RefundService;
import com.sparta.ticket.payment.application.service.refund.RefundTransactions;
import com.sparta.ticket.payment.application.service.settlement.SettlementStatementTransactions.Outcome;
import com.sparta.ticket.payment.domain.model.payment.Payment;
import com.sparta.ticket.payment.domain.model.pg.PgResult;
import com.sparta.ticket.payment.domain.model.refund.RefundReason;
import com.sparta.ticket.payment.domain.repository.payment.PaymentRepository;
import com.sparta.ticket.payment.support.PaymentIntegrationTest;
import com.sparta.ticket.payment.support.SettlementPaymentFlow;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** 판매자·회차별 정산서 발행과 차감 상계(Task 20-5, settlement-design 5·6절, 시험 S4·S5·S8·S9). */
class SettlementStatementIntegrationTest extends PaymentIntegrationTest {

    /** 회차 2026-11-07(토) 19:00 → 정산 가능일 11-11(수) 00:00. 11-12(목) 04:00 발행이면 같은 날 04:10 지급. */
    private static final LocalDateTime CUTOFF = LocalDateTime.of(2026, 11, 12, 4, 0);

    private static final AtomicLong SELLERS = new AtomicLong(50_000);
    private static final AtomicLong SCHEDULES = new AtomicLong(60_000);

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentTransactions paymentTransactions;

    @Autowired
    private RefundService refundService;

    @Autowired
    private RefundTransactions refundTransactions;

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private SettlementStatementTransactions statements;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate tx;

    private SettlementPaymentFlow flow;
    private long seller;
    private long schedule;

    @BeforeEach
    void setUp() {
        flow = new SettlementPaymentFlow(paymentService, paymentTransactions, payments, jdbc, paymentClock);
        pg.cancelReturns(request -> new PgResult.Approved(
                "cancel-" + request.idempotencyKey(), request.amount(), LocalDateTime.now(paymentClock)));
        seller = SELLERS.incrementAndGet();
        schedule = SCHEDULES.incrementAndGet();
    }

    private List<Map<String, Object>> statementsOf(long scheduleId) {
        return jdbc.queryForList(
                "SELECT * FROM p_settlement_statements WHERE seller_id = ? AND schedule_id = ? ORDER BY sequence",
                seller,
                scheduleId);
    }

    private String itemStatus(Payment payment) {
        return jdbc.queryForObject(
                "SELECT status FROM p_settlement_items WHERE payment_id = ? AND kind = 'SALE'",
                String.class,
                payment.getId());
    }

    private Map<String, Long> sellerBalances() {
        return jdbc
                .queryForList(
                        "SELECT e.account, SUM(e.debit - e.credit) AS net FROM p_ledger_entries e"
                                + " JOIN p_ledger_transactions t ON t.id = e.transaction_id WHERE t.seller_id = ?"
                                + " GROUP BY e.account",
                        seller)
                .stream()
                .collect(Collectors.toMap(r -> (String) r.get("account"), r -> ((Number) r.get("net")).longValue()));
    }

    @Test
    void issuesStatementWithLinesAndReadyPayout() {
        Payment first = flow.approvedFor(seller, schedule);
        Payment second = flow.approvedFor(seller, schedule);

        assertThat(statements.issue(seller, schedule, CUTOFF)).isEqualTo(Outcome.ISSUED);

        Map<String, Object> statement = statementsOf(schedule).get(0);
        assertThat(statement)
                .containsEntry("sequence", 0)
                .containsEntry("sale_total", 86_800L)
                .containsEntry("offset_total", 0L)
                .containsEntry("payable_amount", 86_800L)
                .containsEntry("status", "ISSUED");
        long statementId = ((Number) statement.get("id")).longValue();
        assertThat(jdbc.queryForObject(
                        "SELECT sum(amount) FROM p_settlement_statement_lines WHERE statement_id = ?",
                        Long.class,
                        statementId))
                .isEqualTo(86_800L);
        assertThat(itemStatus(first)).isEqualTo("ON_STATEMENT");
        assertThat(itemStatus(second)).isEqualTo("ON_STATEMENT");
        Map<String, Object> payout = jdbc.queryForMap("SELECT * FROM p_payouts WHERE statement_id = ?", statementId);
        assertThat(payout).containsEntry("amount", 86_800L).containsEntry("status", "READY");
        assertThat(payout.get("next_attempt_at").toString()).startsWith("2026-11-12 04:10");
    }

    /** 1회차 발행 뒤 환불 → 차감 43,400. 2회차(판매 43,400) 발행에서 전액 상계되어 지급 없음(NO_PAYOUT). */
    @Test
    void deductionOffsetsNextScheduleAndLedgerMatchesReconciliation() {
        Payment refunded = flow.approvedFor(seller, schedule);
        flow.approvedFor(seller, schedule);
        statements.issue(seller, schedule, CUTOFF);
        refundService.refund(refunded.getOrderId(), RefundReason.SCHEDULE_CANCELED);
        long nextSchedule = SCHEDULES.incrementAndGet();
        flow.approvedFor(seller, nextSchedule);

        assertThat(statements.issue(seller, nextSchedule, CUTOFF)).isEqualTo(Outcome.NO_PAYOUT);

        assertThat(statementsOf(nextSchedule).get(0))
                .containsEntry("sale_total", 43_400L)
                .containsEntry("offset_total", 43_400L)
                .containsEntry("payable_amount", 0L)
                .containsEntry("status", "NO_PAYOUT");
        assertThat(jdbc.queryForMap(
                        "SELECT status, remaining FROM p_settlement_items WHERE refund_id IS NOT NULL"
                                + " AND kind = 'DEDUCTION' AND seller_id = ?",
                        seller))
                .containsEntry("status", "CLEARED")
                .containsEntry("remaining", 0L);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM p_payouts p JOIN p_settlement_statements s ON s.id = p.statement_id"
                                + " WHERE s.schedule_id = ?",
                        Integer.class,
                        nextSchedule))
                .isZero();
        // 대조식(설계 8.1): SELLER_PAYABLE 잔액 = 발행된 정산서 지급액 합(86,800), SELLER_RECEIVABLE = OPEN 잔액 합(0)
        assertThat(sellerBalances()).containsEntry("SELLER_PAYABLE", -86_800L).containsEntry("SELLER_RECEIVABLE", 0L);
    }

    @Test
    void lateSaleGoesToNextSequenceWithoutChangingIssuedStatement() {
        flow.approvedFor(seller, schedule);
        statements.issue(seller, schedule, CUTOFF);
        Payment late = flow.approvedFor(seller, schedule);

        assertThat(statements.issue(seller, schedule, CUTOFF)).isEqualTo(Outcome.ISSUED);

        List<Map<String, Object>> issued = statementsOf(schedule);
        assertThat(issued).hasSize(2);
        assertThat(issued.get(0)).containsEntry("sale_total", 43_400L);
        assertThat(issued.get(1)).containsEntry("sequence", 1).containsEntry("sale_total", 43_400L);
        assertThat(itemStatus(late)).isEqualTo("ON_STATEMENT");
    }

    @Test
    void heldSaleIsExcludedAndNothingIssuedWhenNoneRemain() {
        Payment held = flow.approvedFor(seller, schedule);
        refundTransactions.accept(held.getOrderId(), RefundReason.USER_CANCEL);

        assertThat(statements.issue(seller, schedule, CUTOFF)).isEqualTo(Outcome.NONE);

        assertThat(statementsOf(schedule)).isEmpty();
        assertThat(itemStatus(held)).isEqualTo("HELD");
    }

    @Test
    void concurrentIssuesCreateOneStatement() throws Exception {
        flow.approvedFor(seller, schedule);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Outcome>> runs = List.of(
                    pool.submit(() -> {
                        start.await();
                        return statements.issue(seller, schedule, CUTOFF);
                    }),
                    pool.submit(() -> {
                        start.await();
                        return statements.issue(seller, schedule, CUTOFF);
                    }));
            start.countDown();
            assertThat(List.of(
                            runs.get(0).get(30, TimeUnit.SECONDS), runs.get(1).get(30, TimeUnit.SECONDS)))
                    .containsOnlyOnce(Outcome.ISSUED);
        } finally {
            pool.shutdownNow();
        }
        assertThat(statementsOf(schedule)).hasSize(1);
    }

    /** 지급 행 저장에서 DB 오류가 나면 정산서·명세·항목 상태가 하나도 남지 않고, 다시 발행하면 정상 처리된다. */
    @Test
    void failureBeforeCommitLeavesNothingAndRetryIssues() {
        Payment payment = flow.approvedFor(seller, schedule);
        jdbc.execute("CREATE OR REPLACE FUNCTION fail_payout_for_test() RETURNS trigger AS $$ BEGIN"
                + " IF NEW.seller_id = " + seller + " THEN RAISE EXCEPTION 'injected payout failure'; END IF;"
                + " RETURN NEW; END $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER fail_payout_for_test BEFORE INSERT ON p_payouts"
                + " FOR EACH ROW EXECUTE FUNCTION fail_payout_for_test()");
        try {
            assertThatThrownBy(() -> statements.issue(seller, schedule, CUTOFF)).isInstanceOf(RuntimeException.class);
            assertThat(statementsOf(schedule)).isEmpty();
            assertThat(itemStatus(payment)).isEqualTo("PENDING");
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS fail_payout_for_test ON p_payouts");
            jdbc.execute("DROP FUNCTION IF EXISTS fail_payout_for_test()");
        }

        assertThat(statements.issue(seller, schedule, CUTOFF)).isEqualTo(Outcome.ISSUED);
        assertThat(itemStatus(payment)).isEqualTo("ON_STATEMENT");
    }

    /** 다른 트랜잭션이 SALE을 오래 잡고 있으면 무기한 기다리지 않고 실패한다. 잡은 다음 실행에서 다시 찾는다. */
    @Test
    void lockWaitIsBoundedAndLeavesNothing() throws Exception {
        Payment payment = flow.approvedFor(seller, schedule);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.queryForList(
                        "SELECT id FROM p_settlement_items WHERE payment_id = ? AND kind = 'SALE' FOR UPDATE",
                        payment.getId());
                locked.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            locked.await(10, TimeUnit.SECONDS);

            assertThatThrownBy(() -> statements.issue(seller, schedule, CUTOFF)).isInstanceOf(RuntimeException.class);
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(statementsOf(schedule)).isEmpty();
        assertThat(itemStatus(payment)).isEqualTo("PENDING");
    }
}
