package com.sparta.ticket.payment.application.service.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.payment.application.port.settlement.PayoutAgency;
import com.sparta.ticket.payment.application.port.settlement.PayoutAgency.PayoutAgencyResult;
import com.sparta.ticket.payment.application.service.payment.PaymentService;
import com.sparta.ticket.payment.application.service.payment.PaymentTransactions;
import com.sparta.ticket.payment.application.service.refund.RefundService;
import com.sparta.ticket.payment.application.service.settlement.PayoutExecutionTransactions.Applied;
import com.sparta.ticket.payment.application.service.settlement.PayoutExecutionTransactions.Claim;
import com.sparta.ticket.payment.domain.model.payment.Payment;
import com.sparta.ticket.payment.domain.model.pg.PgResult;
import com.sparta.ticket.payment.domain.model.refund.RefundReason;
import com.sparta.ticket.payment.domain.repository.payment.PaymentRepository;
import com.sparta.ticket.payment.domain.repository.settlement.PayoutRepository;
import com.sparta.ticket.payment.infrastructure.pg.PgProperties;
import com.sparta.ticket.payment.infrastructure.pg.payout.PgPayoutAgency;
import com.sparta.ticket.payment.support.PaymentIntegrationTest;
import com.sparta.ticket.payment.support.SettlementPaymentFlow;
import com.sparta.ticket.payment.support.pg.FakePgAdmin;
import com.sparta.ticket.payment.support.pg.FakePgContainer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

/**
 * 지급 직전 게이트·실행권·최초 실행·결과 반영(Task 20-8, settlement-design 7.2·8.2절, 시험 S6·S8·S10).
 * 지급대행은 fake-pg 컨테이너(처리 지연 300ms)를 실제 HTTP로 부르고, fake-pg로 만들 수 없는 응답(400·422 등)만 스텁으로 넣는다.
 * Note: 다른 시험의 READY 지급을 건드리지 않도록 주기 실행(runOnce) 대신 지급 하나씩 execute한다.
 */
class PayoutExecutionIntegrationTest extends PaymentIntegrationTest {

    private static final LocalDateTime CUTOFF = LocalDateTime.of(2026, 11, 12, 4, 0);
    private static final AtomicLong SELLERS = new AtomicLong(170_000);
    private static final AtomicLong SCHEDULES = new AtomicLong(180_000);

    private final FakePgAdmin admin = new FakePgAdmin(FakePgContainer.baseUrl());

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
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private SettlementPaymentFlow flow;
    private RecordingAgency agency;
    private PayoutExecutionJob job;
    private long seller;
    private long schedule;

    @BeforeEach
    void setUp() {
        admin.clearFaults();
        flow = new SettlementPaymentFlow(paymentService, paymentTransactions, payments, jdbc, paymentClock);
        pg.cancelReturns(request -> new PgResult.Approved(
                "cancel-" + request.idempotencyKey(), request.amount(), LocalDateTime.now(paymentClock)));
        agency = new RecordingAgency(new PgPayoutAgency(RestClient.builder(), properties(), objectMapper));
        job = jobWith(agency, true);
        seller = SELLERS.incrementAndGet();
        schedule = SCHEDULES.incrementAndGet();
    }

    @AfterEach
    void clearFaults() {
        admin.clearFaults();
    }

    private PayoutExecutionJob jobWith(PayoutAgency payoutAgency, boolean enabled) {
        return new PayoutExecutionJob(
                payouts, transactions, payoutAgency, new SimpleMeterRegistry(), paymentClock, enabled, 10);
    }

    private static PgProperties properties() {
        String base = FakePgContainer.baseUrl();
        return new PgProperties(
                Duration.ofSeconds(2),
                Duration.ofSeconds(1),
                new PgProperties.Toss(base, "test_sk_fake_toss"),
                new PgProperties.Kakao(
                        base, "DEV_fake_kakao", "TC0ONETIME", "http://l/approve", "http://l/cancel", "http://l/fail"));
    }

    /** 결제 한 건(판매자 몫 43,400)으로 정산서를 발행하고, 지급을 지금 실행할 수 있게 둔다. */
    private long readyPayout() {
        flow.approvedFor(seller, schedule);
        statements.issue(seller, schedule, CUTOFF);
        return duePayoutOf(seller);
    }

    private long duePayoutOf(long sellerId) {
        jdbc.update(
                "UPDATE p_payouts SET next_attempt_at = ? WHERE seller_id = ? AND status = 'READY'",
                LocalDateTime.now(paymentClock).minusMinutes(1),
                sellerId);
        return jdbc.queryForObject(
                "SELECT max(id) FROM p_payouts WHERE seller_id = ? AND status = 'READY'", Long.class, sellerId);
    }

    private void registerAccount(String accountRef) {
        jdbc.update(
                "UPDATE p_seller_payout_accounts SET bank_code = '004', account_ref = ?, account_masked = '****1234',"
                        + " holder_masked = '홍*동', version = version + 1 WHERE seller_id = ?",
                accountRef,
                seller);
    }

    private Map<String, Object> payout(long payoutId) {
        return jdbc.queryForMap("SELECT * FROM p_payouts WHERE id = ?", payoutId);
    }

    private Map<String, Object> statementOf(long payoutId) {
        return jdbc.queryForMap(
                "SELECT s.* FROM p_settlement_statements s JOIN p_payouts p ON p.statement_id = s.id WHERE p.id = ?",
                payoutId);
    }

    private int payoutJournals(long payoutId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM p_ledger_transactions WHERE source_type = 'PAYOUT' AND source_id = ?",
                Integer.class,
                payoutId);
    }

    /** 지급대행 처리 지연(300ms)이 지나 결과가 확정될 때까지 같은 키로 조회한다. */
    private Applied inquireUntilSettled(long payoutId) throws InterruptedException {
        Applied applied = null;
        for (int i = 0; i < 30; i++) {
            Thread.sleep(100);
            applied = job.inquire(payoutId).orElseThrow();
            if (applied != Applied.IN_PROGRESS) return applied;
        }
        return applied;
    }

    @Test
    void acceptedIsNotDoneAndInquiryCompletesWithPaidStatementAndOneJournal() throws InterruptedException {
        long payoutId = readyPayout();
        registerAccount("acct_normal");

        assertThat(job.execute(payoutId)).contains(Applied.IN_PROGRESS);

        Map<String, Object> requested = payout(payoutId);
        assertThat(requested)
                .containsEntry("status", "REQUESTED")
                .containsEntry("provider_status", "ACCEPTED")
                .containsEntry("attempt", 1)
                .containsEntry("idempotency_key", "payout:" + payoutId + ":1")
                .containsEntry("account_ref", "acct_normal")
                .containsEntry("holder_masked", "홍*동")
                .containsEntry("inquiry_only", false);
        assertThat(requested.get("external_payout_id")).isNotNull();
        assertThat(payoutJournals(payoutId)).isZero();
        assertThat(agency.calledInTransaction).containsOnly(false);

        assertThat(inquireUntilSettled(payoutId)).isEqualTo(Applied.DONE);

        assertThat(payout(payoutId)).containsEntry("status", "DONE").containsEntry("provider_status", null);
        assertThat(payout(payoutId).get("done_at")).isNotNull();
        Map<String, Object> statement = statementOf(payoutId);
        assertThat(statement).containsEntry("status", "PAID");
        assertThat(statement.get("paid_at")).isNotNull();
        assertThat(payoutJournals(payoutId)).isEqualTo(1);
        assertThat(jdbc.queryForMap(
                        "SELECT sum(e.debit) AS debit, sum(e.credit) AS credit,"
                                + " sum(CASE WHEN e.account = 'SELLER_PAYABLE' THEN e.debit ELSE 0 END) AS payable"
                                + " FROM p_ledger_entries e JOIN p_ledger_transactions t ON t.id = e.transaction_id"
                                + " WHERE t.source_type = 'PAYOUT' AND t.source_id = ?",
                        payoutId))
                .containsEntry("debit", java.math.BigDecimal.valueOf(43_400))
                .containsEntry("credit", java.math.BigDecimal.valueOf(43_400))
                .containsEntry("payable", java.math.BigDecimal.valueOf(43_400));

        // 확정된 지급은 다시 조회·실행하지 않고 분개도 늘지 않는다
        assertThat(job.inquire(payoutId)).isEmpty();
        assertThat(job.execute(payoutId)).isEmpty();
        assertThat(payoutJournals(payoutId)).isEqualTo(1);
        assertThat(agency.requests).hasSize(1);
    }

    @Test
    void missingAccountKeepsReadyAndRetriesLaterWithoutCallingAgency() {
        long payoutId = readyPayout();

        assertThat(job.execute(payoutId)).isEmpty();

        Map<String, Object> ready = payout(payoutId);
        assertThat(ready)
                .containsEntry("status", "READY")
                .containsEntry("attempt", 0)
                .containsEntry("last_error", "ACCOUNT_NOT_REGISTERED")
                .containsEntry("idempotency_key", null);
        assertThat(((java.sql.Timestamp) ready.get("next_attempt_at")).toLocalDateTime())
                .isAfter(LocalDateTime.now(paymentClock).plusMinutes(59));
        assertThat(agency.requests).isEmpty();
        // 다음 시도 시각 전에는 다시 잡지 않는다
        assertThat(job.execute(payoutId)).isEmpty();
        assertThat(agency.requests).isEmpty();
    }

    @Test
    void gateBlocksTamperedStatementLineAndPassesAfterFix() {
        long payoutId = readyPayout();
        registerAccount("acct_gate");
        long statementId = ((Number) payout(payoutId).get("statement_id")).longValue();
        jdbc.update("UPDATE p_settlement_statement_lines SET amount = amount + 1 WHERE statement_id = ?", statementId);

        assertThat(job.execute(payoutId)).isEmpty();
        assertThat(payout(payoutId))
                .containsEntry("status", "READY")
                .containsEntry("last_error", "GATE_SALE_LINES_TOTAL");
        assertThat(agency.requests).isEmpty();

        // 데이터가 고쳐지면 다음 시도에 자동 통과한다(게이트 결과를 따로 저장하지 않는다)
        jdbc.update("UPDATE p_settlement_statement_lines SET amount = amount - 1 WHERE statement_id = ?", statementId);
        duePayoutOf(seller);
        assertThat(job.execute(payoutId)).contains(Applied.IN_PROGRESS);
    }

    /** 1회차 발행 뒤 환불 → 차감 43,400. 2회차 판매 86,800에서 부분 상계해 지급 43,400. */
    private long partiallyOffsetPayout() {
        Payment refunded = flow.approvedFor(seller, schedule);
        statements.issue(seller, schedule, CUTOFF);
        refundService.refund(refunded.getOrderId(), RefundReason.SCHEDULE_CANCELED);
        long next = SCHEDULES.incrementAndGet();
        flow.approvedFor(seller, next);
        flow.approvedFor(seller, next);
        statements.issue(seller, next, CUTOFF);
        return jdbc.queryForObject(
                "SELECT p.id FROM p_payouts p JOIN p_settlement_statements s ON s.id = p.statement_id"
                        + " WHERE s.seller_id = ? AND s.schedule_id = ?",
                Long.class,
                seller,
                next);
    }

    @Test
    void gatePassesNormalPartialOffsetButBlocksTamperedDeduction() {
        long payoutId = partiallyOffsetPayout();
        registerAccount("acct_offset");
        jdbc.update(
                "UPDATE p_payouts SET next_attempt_at = ? WHERE id = ?",
                LocalDateTime.now(paymentClock).minusMinutes(1),
                payoutId);
        assertThat(statementOf(payoutId))
                .containsEntry("offset_total", 43_400L)
                .containsEntry("payable_amount", 43_400L);
        jdbc.update(
                "UPDATE p_settlement_items SET principal = principal + 100 WHERE seller_id = ? AND kind = 'DEDUCTION'",
                seller);

        assertThat(job.execute(payoutId)).isEmpty();
        assertThat(payout(payoutId)).containsEntry("last_error", "GATE_DEDUCTION_ALLOCATION");

        jdbc.update(
                "UPDATE p_settlement_items SET principal = principal - 100 WHERE seller_id = ? AND kind = 'DEDUCTION'",
                seller);
        jdbc.update(
                "UPDATE p_payouts SET next_attempt_at = ? WHERE id = ?",
                LocalDateTime.now(paymentClock).minusMinutes(1),
                payoutId);
        assertThat(job.execute(payoutId)).contains(Applied.IN_PROGRESS);
        assertThat(payout(payoutId)).containsEntry("amount", 43_400L);
    }

    @Test
    void agencyFailureIsFailedWithoutJournalAndStatementStaysIssued() throws InterruptedException {
        long payoutId = readyPayout();
        registerAccount("INVALID-acct");

        assertThat(job.execute(payoutId)).contains(Applied.IN_PROGRESS);
        assertThat(inquireUntilSettled(payoutId)).isEqualTo(Applied.FAILED);

        assertThat(payout(payoutId)).containsEntry("status", "FAILED").containsEntry("failure_code", "ACCOUNT_INVALID");
        assertThat(statementOf(payoutId)).containsEntry("status", "ISSUED");
        assertThat(payoutJournals(payoutId)).isZero();
    }

    @Test
    void tamperedAmountBlocksToInquiryOnlyAndKeepsSnapshot() throws InterruptedException {
        long payoutId = readyPayout();
        registerAccount("acct_tamper");
        admin.fault("TOSS", "PAYOUT", "{\"tamperRate\":1,\"tamperMode\":\"WRONG_AMOUNT\"}");

        assertThat(job.execute(payoutId)).contains(Applied.INQUIRY_ONLY);

        assertThat(payout(payoutId))
                .containsEntry("status", "REQUESTED")
                .containsEntry("inquiry_only", true)
                .containsEntry("verification_reason", "AMOUNT_MISMATCH")
                .containsEntry("amount", 43_400L);

        // 조회가 스냅샷과 맞게 확정되면 DONE
        assertThat(inquireUntilSettled(payoutId)).isEqualTo(Applied.DONE);
        assertThat(payoutJournals(payoutId)).isEqualTo(1);
    }

    @Test
    void lostResponseStaysRequestedUnknownAndInquiryFindsIt() throws InterruptedException {
        long payoutId = readyPayout();
        registerAccount("acct_lost");
        admin.fault("TOSS", "PAYOUT", "{\"responseLossRate\":1,\"holdMs\":1500}");

        assertThat(job.execute(payoutId)).contains(Applied.UNKNOWN);
        assertThat(payout(payoutId))
                .containsEntry("status", "REQUESTED")
                .containsEntry("provider_status", "UNKNOWN")
                .containsEntry("external_payout_id", null);

        assertThat(inquireUntilSettled(payoutId)).isEqualTo(Applied.DONE);
    }

    @Test
    void requestRejectionsAndUnknownResponsesAreClassified() {
        assertThat(applyStubbed(new PayoutAgencyResult.RequestInvalid("400"))).isEqualTo(Applied.FAILED);
        assertThat(payout(lastPayout)).containsEntry("failure_code", "REQUEST_INVALID");

        assertThat(applyStubbed(new PayoutAgencyResult.KeyConflict("422"))).isEqualTo(Applied.INQUIRY_ONLY);
        assertThat(payout(lastPayout)).containsEntry("verification_reason", "KEY_CONFLICT");

        assertThat(applyStubbed(new PayoutAgencyResult.Malformed("bad"))).isEqualTo(Applied.INQUIRY_ONLY);

        assertThat(applyStubbed(new PayoutAgencyResult.Unknown("지급대행 응답 503"))).isEqualTo(Applied.UNKNOWN);
        assertThat(payout(lastPayout))
                .containsEntry("status", "REQUESTED")
                .containsEntry("failure_code", null)
                .containsEntry("last_error", "지급대행 응답 503");

        assertThat(applyStubbed(order -> new PayoutAgencyResult.Observed(
                        PayoutAgency.AgencyStatus.COMPLETED,
                        "po_other",
                        order.idempotencyKey(),
                        "acct_other",
                        order.amount(),
                        null)))
                .isEqualTo(Applied.INQUIRY_ONLY);
        assertThat(payout(lastPayout)).containsEntry("verification_reason", "ACCOUNT_MISMATCH");
    }

    @Test
    void staleClaimDoesNotOverwriteAndDisabledSwitchSendsNothing() {
        long payoutId = readyPayout();
        registerAccount("acct_stale");
        assertThat(jobWith(agency, false).execute(payoutId)).isEmpty();
        assertThat(payout(payoutId)).containsEntry("status", "READY");

        Claim claim = transactions.claim(payoutId).orElseThrow();
        Claim stale = new Claim(claim.payoutId(), claim.attempt() - 1, "payout:" + payoutId + ":0", claim.order());

        assertThat(transactions.apply(stale, new PayoutAgencyResult.RequestInvalid("late")))
                .isEqualTo(Applied.STALE);
        assertThat(payout(payoutId)).containsEntry("status", "REQUESTED").containsEntry("failure_code", null);
    }

    private long lastPayout;

    private Applied applyStubbed(PayoutAgencyResult result) {
        return applyStubbed(order -> result);
    }

    private Applied applyStubbed(Function<PayoutAgency.PayoutOrder, PayoutAgencyResult> response) {
        seller = SELLERS.incrementAndGet();
        schedule = SCHEDULES.incrementAndGet();
        lastPayout = readyPayout();
        registerAccount("acct_stub");
        PayoutAgency stub = new PayoutAgency() {
            @Override
            public PayoutAgencyResult request(PayoutOrder order) {
                return response.apply(order);
            }

            @Override
            public PayoutAgencyResult inquire(String idempotencyKey) {
                throw new UnsupportedOperationException();
            }
        };
        Optional<Applied> applied = jobWith(stub, true).execute(lastPayout);
        return applied.orElseThrow();
    }

    /** 실제 어댑터를 감싸 호출 기록과 호출 시점의 트랜잭션 여부를 남긴다. */
    private static final class RecordingAgency implements PayoutAgency {
        private final PayoutAgency delegate;
        final List<PayoutOrder> requests = new ArrayList<>();
        final List<Boolean> calledInTransaction = new ArrayList<>();

        RecordingAgency(PayoutAgency delegate) {
            this.delegate = delegate;
        }

        @Override
        public PayoutAgencyResult request(PayoutOrder order) {
            requests.add(order);
            calledInTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
            return delegate.request(order);
        }

        @Override
        public PayoutAgencyResult inquire(String idempotencyKey) {
            calledInTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
            return delegate.inquire(idempotencyKey);
        }
    }
}
