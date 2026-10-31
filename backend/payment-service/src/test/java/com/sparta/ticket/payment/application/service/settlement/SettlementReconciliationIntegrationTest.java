package com.sparta.ticket.payment.application.service.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.payment.application.port.settlement.PayoutAgency;
import com.sparta.ticket.payment.application.service.payment.PaymentService;
import com.sparta.ticket.payment.application.service.payment.PaymentTransactions;
import com.sparta.ticket.payment.application.service.refund.RefundService;
import com.sparta.ticket.payment.application.service.settlement.PayoutExecutionTransactions.Applied;
import com.sparta.ticket.payment.application.service.settlement.SettlementReconciliationJob.Report;
import com.sparta.ticket.payment.domain.model.payment.Payment;
import com.sparta.ticket.payment.domain.model.pg.PgResult;
import com.sparta.ticket.payment.domain.model.refund.RefundReason;
import com.sparta.ticket.payment.domain.repository.payment.PaymentRepository;
import com.sparta.ticket.payment.domain.repository.settlement.PayoutRepository;
import com.sparta.ticket.payment.domain.repository.settlement.SettlementReconcileRepository;
import com.sparta.ticket.payment.domain.repository.settlement.SettlementReconcileRepository.CheckResult;
import com.sparta.ticket.payment.infrastructure.observation.PaymentObservationTransactions;
import com.sparta.ticket.payment.infrastructure.observation.PaymentObservationTransactions.Sample;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.SettlementReconcileJdbcAdapter;
import com.sparta.ticket.payment.infrastructure.pg.PgProperties;
import com.sparta.ticket.payment.infrastructure.pg.payout.PgPayoutAgency;
import com.sparta.ticket.payment.support.PaymentIntegrationTest;
import com.sparta.ticket.payment.support.SettlementPaymentFlow;
import com.sparta.ticket.payment.support.pg.FakePgContainer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

/**
 * 정산 전체 대조(Task 20-11, settlement-design 8.1·8.2절, 시험 S10). 판매·발행 전 환불·발행 뒤 환불·부분 상계·지급 완료까지 거친
 * 정상 데이터는 모든 항목이 0이고, 데이터를 하나씩 망가뜨리면 해당 항목이 그 행을 짚는다. 대조는 고치지 않는다.
 * Note: 대조는 테이블 전체를 보므로 시험마다 정산·결제 테이블을 비운다(다른 결제 통합 시험과 같은 방식).
 */
class SettlementReconciliationIntegrationTest extends PaymentIntegrationTest {

    private static final LocalDateTime CUTOFF = LocalDateTime.of(2026, 11, 12, 4, 0);
    private static final long SELLER = 470_001L;
    private static final long OTHER_SELLER = 470_002L;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentTransactions paymentTransactions;

    @Autowired
    private RefundService refundService;

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private SettlementStatementTransactions statements;

    @Autowired
    private PayoutExecutionTransactions transactions;

    @Autowired
    private PayoutRepository payouts;

    @Autowired
    private SettlementReconcileRepository repository;

    @Autowired
    private PaymentObservationTransactions observation;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private SettlementPaymentFlow flow;
    private PayoutAgency agency;
    private PayoutExecutionJob payoutJob;
    private SettlementReconciliationJob job;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM p_settlement_reconcile_results");
        jdbc.update("DELETE FROM p_settlement_reconcile_runs");
        jdbc.update("DELETE FROM p_ledger_entries");
        jdbc.update("DELETE FROM p_ledger_transactions");
        jdbc.update("DELETE FROM p_settlement_statement_lines");
        jdbc.update("DELETE FROM p_payout_operation_commands");
        jdbc.update("DELETE FROM p_payouts");
        jdbc.update("DELETE FROM p_settlement_items");
        jdbc.update("DELETE FROM p_settlement_statements");
        jdbc.update("DELETE FROM p_refunds");
        jdbc.update("DELETE FROM p_payments");
        flow = new SettlementPaymentFlow(paymentService, paymentTransactions, payments, jdbc, paymentClock);
        pg.cancelReturns(request -> new PgResult.Approved(
                "cancel-" + request.idempotencyKey(), request.amount(), LocalDateTime.now(paymentClock)));
        String base = FakePgContainer.baseUrl();
        agency = new PgPayoutAgency(
                RestClient.builder(),
                new PgProperties(
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(2),
                        new PgProperties.Toss(base, "test_sk_fake_toss"),
                        new PgProperties.Kakao(
                                base, "DEV_fake_kakao", "TC0ONETIME", "http://l/a", "http://l/c", "http://l/f")),
                objectMapper);
        payoutJob = new PayoutExecutionJob(
                payouts, transactions, agency, new SimpleMeterRegistry(), paymentClock, true, 10, 168);
        job = new SettlementReconciliationJob(repository, agency, paymentClock, "", 48, 500);
    }

    /** 대조 대상 원천 ID. */
    private record Scenario(
            Payment settledSale,
            Payment refundedAfterStatement,
            Payment canceledBeforeStatement,
            long paidStatement,
            long offsetStatement,
            long donePayout) {}

    /**
     * 판매자 A: 1회차 2건 발행(86,800) → 1건 발행 뒤 환불(차감 43,400) → 2회차 2건 발행에서 부분 상계(지급 43,400) → 두 지급 완료.
     * 판매자 B: 발행 전 환불(SALE 취소·반대 분개) 1건, 발행 전 판매(PENDING) 1건.
     */
    private Scenario scenario() throws InterruptedException {
        Payment settled = flow.approvedFor(SELLER, 1L);
        Payment refunded = flow.approvedFor(SELLER, 1L);
        statements.issue(SELLER, 1L, CUTOFF);
        refundService.refund(refunded.getOrderId(), RefundReason.SCHEDULE_CANCELED);
        flow.approvedFor(SELLER, 2L);
        flow.approvedFor(SELLER, 2L);
        statements.issue(SELLER, 2L, CUTOFF);
        Payment canceled = flow.approvedFor(OTHER_SELLER, 3L);
        refundService.refund(canceled.getOrderId(), RefundReason.SCHEDULE_CANCELED);
        flow.approvedFor(OTHER_SELLER, 4L);

        jdbc.update(
                "UPDATE p_seller_payout_accounts SET bank_code = '004', account_ref = 'acct_recon',"
                        + " account_masked = '****1234', holder_masked = '홍*동', version = version + 1 WHERE seller_id = ?",
                SELLER);
        jdbc.update(
                "UPDATE p_payouts SET next_attempt_at = ?",
                LocalDateTime.now(paymentClock).minusMinutes(1));
        List<Long> payoutIds = jdbc.queryForList("SELECT id FROM p_payouts ORDER BY id", Long.class);
        for (Long id : payoutIds) assertThat(payoutJob.execute(id)).contains(Applied.IN_PROGRESS);
        for (Long id : payoutIds) assertThat(settle(id)).isEqualTo(Applied.DONE);

        long paid = jdbc.queryForObject("SELECT id FROM p_settlement_statements WHERE schedule_id = 1", Long.class);
        long offset = jdbc.queryForObject("SELECT id FROM p_settlement_statements WHERE schedule_id = 2", Long.class);
        long donePayout = jdbc.queryForObject("SELECT id FROM p_payouts WHERE statement_id = ?", Long.class, paid);
        return new Scenario(settled, refunded, canceled, paid, offset, donePayout);
    }

    private Applied settle(long payoutId) throws InterruptedException {
        Applied applied = null;
        for (int i = 0; i < 30; i++) {
            Thread.sleep(100);
            jdbc.update(
                    "UPDATE p_payouts SET next_attempt_at = ? WHERE id = ? AND status = 'REQUESTED'",
                    LocalDateTime.now(paymentClock).minusSeconds(1),
                    payoutId);
            applied = payoutJob.recover(payoutId).orElseThrow();
            if (applied != Applied.IN_PROGRESS) return applied;
        }
        return applied;
    }

    private static Map<String, CheckResult> byCheck(Report report) {
        return report.results().stream().collect(Collectors.toMap(CheckResult::check, r -> r));
    }

    private static List<String> sample(Map<String, CheckResult> results, String check) {
        String sample = results.get(check).sample();
        return sample == null ? List.of() : Arrays.asList(sample.split(","));
    }

    @Test
    void consistentSettlementPassesEveryCheckAndRecordsRun() throws Exception {
        scenario();

        Report report = job.run();

        assertThat(report.results())
                .extracting(CheckResult::check)
                .containsExactlyElementsOf(concat(
                        SettlementReconcileJdbcAdapter.checkNames(), SettlementReconciliationJob.PROVIDER_CHECK));
        assertThat(report.results())
                .allSatisfy(result -> assertThat(result.violations())
                        .as(result.check() + " " + result.sample())
                        .isZero());
        assertThat(report.providerUnreachable()).isZero();
        assertThat(jdbc.queryForMap("SELECT * FROM p_settlement_reconcile_runs WHERE id = ?", report.runId()))
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("violations", 0L)
                .containsEntry("provider_checked", 2)
                .containsEntry("provider_unreachable", 0);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM p_settlement_reconcile_results WHERE run_id = ?",
                        Integer.class,
                        report.runId()))
                .isEqualTo(report.results().size());
    }

    @Test
    void eachCorruptionIsPointedByItsCheckWithoutRepair() throws Exception {
        Scenario s = scenario();
        long settledSale = jdbc.queryForObject(
                "SELECT id FROM p_settlement_items WHERE payment_id = ? AND kind = 'SALE'",
                Long.class,
                s.settledSale().getId());
        long saleJournal = jdbc.queryForObject(
                "SELECT id FROM p_ledger_transactions WHERE source_type = 'SALE' AND source_id = ?",
                Long.class,
                s.settledSale().getId());
        long deduction = jdbc.queryForObject("SELECT id FROM p_settlement_items WHERE kind = 'DEDUCTION'", Long.class);
        long pendingPayment = jdbc.queryForObject(
                "SELECT payment_id FROM p_settlement_items WHERE kind = 'SALE' AND status = 'PENDING'", Long.class);

        // ③ 판매 누락(덜 지급): 승인 결제의 SALE·분개가 사라짐
        flow.dropSettlement(pendingPayment);
        // ③ 원장 불균형: 판매 분개의 한 줄만 금액이 바뀜
        jdbc.update(
                "UPDATE p_ledger_entries SET credit = credit + 1 WHERE transaction_id = ? AND account = 'COMMISSION_REVENUE'",
                saleJournal);
        // ③ 판매자 몫과 원장이 다름(균형은 맞음): SELLER_PAYABLE과 PG_CLEARING을 같이 늘림
        jdbc.update(
                "UPDATE p_ledger_entries SET credit = credit + 100 WHERE transaction_id = ? AND account = 'SELLER_PAYABLE'",
                saleJournal);
        jdbc.update(
                "UPDATE p_ledger_entries SET debit = debit + 100 WHERE transaction_id = ? AND account = 'PG_CLEARING'",
                saleJournal);
        // ③ 정산서 합계가 명세와 다름
        jdbc.update(
                "UPDATE p_settlement_statements SET sale_total = sale_total + 1, payable_amount = payable_amount + 1"
                        + " WHERE id = ?",
                s.offsetStatement());
        // ③ 차감 배분 불일치(과다 상계·회수 누락)와 미회수 잔액 불일치: 원장은 다 회수했는데 차감은 100원이 남았다고 함
        jdbc.update(
                "UPDATE p_settlement_items SET principal = principal + 50, status = 'OPEN', remaining = 100 WHERE id = ?",
                deduction);
        // ① 지급 완료 상태가 정산서·원장과 어긋남
        jdbc.update(
                "UPDATE p_settlement_statements SET status = 'ISSUED', paid_at = NULL WHERE id = ?", s.paidStatement());
        // ① 지급대행 기록과 다른 거래 ID
        jdbc.update("UPDATE p_payouts SET external_payout_id = 'po_tampered' WHERE id = ?", s.donePayout());
        String before = jdbc.queryForObject(
                "SELECT string_agg(id || ':' || status || ':' || coalesce(external_payout_id, ''), ',' ORDER BY id)"
                        + " FROM p_payouts",
                String.class);

        Report report = job.run();
        Map<String, CheckResult> results = byCheck(report);

        assertThat(sample(results, "SALE_MISSING")).contains(String.valueOf(pendingPayment));
        assertThat(sample(results, "LEDGER_UNBALANCED")).contains(String.valueOf(saleJournal));
        assertThat(sample(results, "SALE_JOURNAL")).contains(String.valueOf(settledSale));
        assertThat(sample(results, "SELLER_PAYABLE_BALANCE")).contains(String.valueOf(SELLER));
        assertThat(sample(results, "STATEMENT_TOTALS")).contains(String.valueOf(s.offsetStatement()));
        assertThat(sample(results, "STATEMENT_PAYOUT")).contains(String.valueOf(s.offsetStatement()));
        assertThat(sample(results, "DEDUCTION_ALLOCATION")).contains(String.valueOf(deduction));
        assertThat(sample(results, "DEDUCTION_JOURNAL")).contains(String.valueOf(deduction));
        assertThat(sample(results, "SELLER_RECEIVABLE_BALANCE")).contains(String.valueOf(SELLER));
        assertThat(sample(results, "PAYOUT_COMPLETION")).contains(String.valueOf(s.donePayout()));
        assertThat(sample(results, SettlementReconciliationJob.PROVIDER_CHECK))
                .contains(String.valueOf(s.donePayout()));
        assertThat(report.violations()).isPositive();
        // 손대지 않은 항목은 그대로 0이다
        assertThat(results.get("SALE_DUPLICATE").violations()).isZero();
        assertThat(results.get("CANCELED_WITHOUT_REVERSE").violations()).isZero();
        assertThat(results.get("PAYOUT_CLEARING_BALANCE").violations()).isZero();
        // 대조는 고치지 않는다
        assertThat(jdbc.queryForObject(
                        "SELECT string_agg(id || ':' || status || ':' || coalesce(external_payout_id, ''), ',' ORDER BY id)"
                                + " FROM p_payouts",
                        String.class))
                .isEqualTo(before);

        // 경보가 보는 지표가 마지막 성공 실행의 위반 수를 낸다
        List<Sample> samples = observation.read();
        assertThat(samples)
                .contains(new Sample("settlement.reconcile.violations", List.of("check", "SALE_MISSING"), 1))
                .contains(new Sample("settlement.reconcile.last.failed", List.of(), 0));
        assertThat(samples).anySatisfy(sample -> {
            assertThat(sample.name()).isEqualTo("settlement.reconcile.last.success.timestamp.seconds");
            assertThat(sample.value()).isPositive();
        });
    }

    @Test
    void refundReflectionHoldAndClearingChecks() throws Exception {
        Scenario s = scenario();
        long canceledSale = jdbc.queryForObject(
                "SELECT id FROM p_settlement_items WHERE payment_id = ? AND kind = 'SALE'",
                Long.class,
                s.canceledBeforeStatement().getId());
        long refundAfter = jdbc.queryForObject(
                "SELECT id FROM p_refunds WHERE payment_id = ?",
                Long.class,
                s.refundedAfterStatement().getId());
        long pendingSale = jdbc.queryForObject(
                "SELECT id FROM p_settlement_items WHERE kind = 'SALE' AND status = 'PENDING'", Long.class);
        long pendingPayment =
                jdbc.queryForObject("SELECT payment_id FROM p_settlement_items WHERE id = ?", Long.class, pendingSale);

        // ① 발행 뒤 환불 차감이 사라짐 → 환불된 돈을 회수하지 못함(상계했던 명세행도 함께 사라져 정산서 합계도 어긋남)
        jdbc.update("DELETE FROM p_settlement_statement_lines WHERE kind = 'DEDUCTION'");
        jdbc.update("DELETE FROM p_settlement_items WHERE kind = 'DEDUCTION'");
        // ③ 발행 전 환불의 반대 분개가 사라짐
        jdbc.update("DELETE FROM p_ledger_entries WHERE transaction_id IN (SELECT id FROM p_ledger_transactions"
                + " WHERE source_type = 'REFUND' AND action = 'REVERSE')");
        jdbc.update("DELETE FROM p_ledger_transactions WHERE source_type = 'REFUND' AND action = 'REVERSE'");
        // ① 환불이 진행 중인데 판매가 정산 대상(PENDING)으로 남음
        jdbc.update(
                "INSERT INTO p_refunds (payment_id, reservation_id, refund_amount, reason, status,"
                        + " created_at, created_by, updated_at, updated_by)"
                        + " SELECT id, reservation_id, 45000, 'USER_CANCEL', 'REQUESTED', now(), 't', now(), 't'"
                        + " FROM p_payments WHERE id = ?",
                pendingPayment);
        // ① 지급 분개가 사라짐 → 지급 확정 누적액과 DONE 지급액이 다름
        jdbc.update(
                "DELETE FROM p_ledger_entries WHERE transaction_id IN (SELECT id FROM p_ledger_transactions"
                        + " WHERE source_type = 'PAYOUT' AND source_id = ?)",
                s.donePayout());
        jdbc.update("DELETE FROM p_ledger_transactions WHERE source_type = 'PAYOUT' AND source_id = ?", s.donePayout());

        Map<String, CheckResult> results = byCheck(job.run());

        assertThat(sample(results, "REFUND_NOT_REFLECTED")).contains(String.valueOf(refundAfter));
        assertThat(sample(results, "STATEMENT_TOTALS")).contains(String.valueOf(s.offsetStatement()));
        assertThat(sample(results, "CANCELED_WITHOUT_REVERSE")).contains(String.valueOf(canceledSale));
        assertThat(sample(results, "SALE_NOT_HELD_FOR_REFUND")).contains(String.valueOf(pendingSale));
        assertThat(results.get("PAYOUT_CLEARING_BALANCE").violations()).isEqualTo(1);
        assertThat(sample(results, "PAYOUT_COMPLETION")).contains(String.valueOf(s.donePayout()));
    }

    @Test
    void failedRunIsNotSuccessAndKeepsPreviousSuccess() throws Exception {
        scenario();
        Report first = job.run();
        SettlementReconcileRepository broken = new SettlementReconcileRepository() {
            @Override
            public List<CheckResult> runChecks(LocalDateTime paymentsSince) {
                throw new IllegalStateException("db down");
            }

            @Override
            public List<DonePayout> findDonePayouts(LocalDateTime doneSince, int limit) {
                return repository.findDonePayouts(doneSince, limit);
            }

            @Override
            public long startRun(LocalDateTime now) {
                return repository.startRun(now);
            }

            @Override
            public void finishRun(long runId, Finished finished) {
                repository.finishRun(runId, finished);
            }
        };

        assertThatThrownBy(() -> new SettlementReconciliationJob(broken, agency, paymentClock, "", 48, 500).run())
                .isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForList("SELECT status FROM p_settlement_reconcile_runs ORDER BY id", String.class))
                .containsExactly("SUCCEEDED", "FAILED");
        List<Sample> samples = observation.read();
        assertThat(samples).contains(new Sample("settlement.reconcile.last.failed", List.of(), 1));
        assertThat(samples)
                .filteredOn(sample -> sample.name().equals("settlement.reconcile.last.success.timestamp.seconds"))
                .singleElement()
                .satisfies(sample -> assertThat(sample.value()).isPositive());
        assertThat(first.violations()).isZero();
    }

    @Test
    void providerOutageIsUnconfirmedNotViolation() throws Exception {
        scenario();
        PayoutAgency down = new PayoutAgency() {
            @Override
            public PayoutAgencyResult request(PayoutOrder order) {
                throw new UnsupportedOperationException();
            }

            @Override
            public PayoutAgencyResult inquire(String idempotencyKey) {
                return new PayoutAgencyResult.Unknown("503");
            }
        };

        Report report = new SettlementReconciliationJob(repository, down, paymentClock, "", 48, 500).run();

        assertThat(byCheck(report)
                        .get(SettlementReconciliationJob.PROVIDER_CHECK)
                        .violations())
                .isZero();
        assertThat(report.providerUnreachable()).isEqualTo(2);
    }

    @Test
    void riskGaugesReportPayoutAttentionAndOpenDeduction() throws Exception {
        Payment refunded = flow.approvedFor(SELLER, 1L);
        flow.approvedFor(SELLER, 1L);
        statements.issue(SELLER, 1L, CUTOFF);
        refundService.refund(refunded.getOrderId(), RefundReason.SCHEDULE_CANCELED);

        List<Sample> samples = observation.read();

        // 계좌를 등록하지 않아 정산서 지급이 READY로 남고, 발행 뒤 환불 차감 43,400이 미회수로 남는다
        assertThat(samples)
                .contains(new Sample("settlement.db.payouts", List.of("attention", "READY"), 1))
                .contains(new Sample("settlement.db.payouts.amount.won", List.of("attention", "READY"), 86_800))
                .contains(new Sample("settlement.db.deductions.open", List.of(), 1))
                .contains(new Sample("settlement.db.deductions.open.amount.won", List.of(), 43_400))
                .contains(new Sample("settlement.db.sales.unposted", List.of(), 0));
        assertThat(samples)
                .filteredOn(sample -> sample.name().equals("settlement.db.jobs.due"))
                .extracting(sample -> sample.tags().get(1))
                .containsExactlyInAnyOrder("statement", "payout", "payout_recovery");
    }

    private static List<String> concat(List<String> names, String last) {
        return java.util.stream.Stream.concat(names.stream(), java.util.stream.Stream.of(last))
                .toList();
    }
}
