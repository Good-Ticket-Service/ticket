package com.sparta.ticket.payment.application.service.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.common.auth.UserHeaders;
import com.sparta.ticket.payment.application.port.settlement.PayoutAgency;
import com.sparta.ticket.payment.application.port.settlement.PayoutAgency.AgencyStatus;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.client.RestClient;

/**
 * 지급 운영 retry·관리자 조회(Task 20-10, settlement-design 7.3·7.4절, 시험 S7). 확정 실패(FAILED)만 retry로 READY가 되고, 다음
 * 실행이 attempt+1·새 키로 다시 보낸다. retry 뒤 늦게 도착한 이전 attempt 응답이 새 시도를 바꾸지 못하는 것(세대 방어)도 확인한다.
 * 실패는 fake-pg의 지급 거절 장애(failRate)로 만든다.
 */
class PayoutOperationIntegrationTest extends PaymentIntegrationTest {

    private static final LocalDateTime CUTOFF = LocalDateTime.of(2026, 11, 12, 4, 0);
    private static final AtomicLong SELLERS = new AtomicLong(370_000);
    private static final AtomicLong SCHEDULES = new AtomicLong(380_000);
    private static final AtomicLong COMMANDS = new AtomicLong();
    private static final long MASTER = 9_001L;

    private final FakePgAdmin admin = new FakePgAdmin(FakePgContainer.baseUrl());

    @Autowired
    private MockMvc mockMvc;

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
    private PayoutAgency agency;
    private PayoutExecutionJob job;
    private long seller;

    @BeforeEach
    void setUp() {
        admin.clearFaults();
        flow = new SettlementPaymentFlow(paymentService, paymentTransactions, payments, jdbc, paymentClock);
        pg.cancelReturns(request -> new PgResult.Approved(
                "cancel-" + request.idempotencyKey(), request.amount(), LocalDateTime.now(paymentClock)));
        agency = new PgPayoutAgency(RestClient.builder(), properties(), objectMapper);
        job = new PayoutExecutionJob(
                payouts, transactions, agency, new SimpleMeterRegistry(), paymentClock, true, 10, 168);
        seller = SELLERS.incrementAndGet();
    }

    @AfterEach
    void clearFaults() {
        admin.clearFaults();
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

    private long readyPayout() {
        long schedule = SCHEDULES.incrementAndGet();
        flow.approvedFor(seller, schedule);
        statements.issue(seller, schedule, CUTOFF);
        jdbc.update(
                "UPDATE p_seller_payout_accounts SET bank_code = '004', account_ref = ?, account_masked = '****1234',"
                        + " holder_masked = '홍*동', version = version + 1 WHERE seller_id = ?",
                "acct_" + seller,
                seller);
        long payoutId = jdbc.queryForObject(
                "SELECT p.id FROM p_payouts p JOIN p_settlement_statements s ON s.id = p.statement_id"
                        + " WHERE s.seller_id = ? AND s.schedule_id = ?",
                Long.class,
                seller,
                schedule);
        due(payoutId);
        return payoutId;
    }

    private void due(long payoutId) {
        jdbc.update(
                "UPDATE p_payouts SET next_attempt_at = ? WHERE id = ?",
                LocalDateTime.now(paymentClock).minusSeconds(1),
                payoutId);
    }

    /** 지급대행이 거절을 확정할 때까지 조회한다. */
    private Applied settle(long payoutId) throws InterruptedException {
        Applied applied = null;
        for (int i = 0; i < 30; i++) {
            Thread.sleep(100);
            due(payoutId);
            applied = job.recover(payoutId).orElseThrow();
            if (applied != Applied.IN_PROGRESS) return applied;
        }
        return applied;
    }

    /** 지급대행 거절(REJECTED)로 확정 실패한 지급. */
    private long failedPayout() throws InterruptedException {
        long payoutId = readyPayout();
        admin.fault("TOSS", "PAYOUT", "{\"failRate\":1}");
        assertThat(job.execute(payoutId)).contains(Applied.IN_PROGRESS);
        assertThat(settle(payoutId)).isEqualTo(Applied.FAILED);
        admin.clearFaults();
        assertThat(payout(payoutId)).containsEntry("failure_code", "REJECTED");
        return payoutId;
    }

    private Map<String, Object> payout(long payoutId) {
        return jdbc.queryForMap("SELECT * FROM p_payouts WHERE id = ?", payoutId);
    }

    private long version(long payoutId) {
        return ((Number) payout(payoutId).get("version")).longValue();
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, long userId, String role) {
        return request.header(UserHeaders.USER_ID, String.valueOf(userId))
                .header(UserHeaders.USERNAME, "master@ticket.com")
                .header(UserHeaders.ROLE, role);
    }

    private ResultActions retry(long payoutId, String commandId, String note, long version, int attempt, long user)
            throws Exception {
        String body = "{\"commandId\":\"" + commandId + "\",\"note\":\"" + note + "\",\"expectedVersion\":" + version
                + ",\"expectedAttempt\":" + attempt + "}";
        return mockMvc.perform(as(post("/api/admin/settlements/payouts/{id}/retry", payoutId), user, "MASTER")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String command() {
        return "cmd-retry-" + COMMANDS.incrementAndGet();
    }

    @Test
    void confirmedFailureRetriesWithNewKeyAndCompletes() throws Exception {
        long payoutId = failedPayout();
        long version = version(payoutId);
        mockMvc.perform(as(get("/api/admin/settlements/payouts/{id}", payoutId), MASTER, "MASTER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current.attention").value("FAILED"))
                .andExpect(jsonPath("$.data.current.failureCode").value("REJECTED"))
                .andExpect(jsonPath("$.data.current.idempotencyKey").doesNotExist())
                .andExpect(jsonPath("$.data.current.accountRef").doesNotExist());

        String commandId = command();
        retry(payoutId, commandId, "지급대행 반려 사유 확인 후 재요청", version, 1, MASTER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("APPLIED"))
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.attempt").value(1))
                .andExpect(jsonPath("$.data.version").value(version + 1));
        assertThat(payout(payoutId))
                .containsEntry("status", "READY")
                .containsEntry("failure_code", null)
                .containsEntry("idempotency_key", null)
                .containsEntry("version", version + 1);

        // 다음 실행이 attempt+1·새 키로 다시 보내고 조회로 확정한다
        assertThat(job.execute(payoutId)).contains(Applied.IN_PROGRESS);
        assertThat(payout(payoutId))
                .containsEntry("attempt", 2)
                .containsEntry("idempotency_key", "payout:" + payoutId + ":2");
        assertThat(settle(payoutId)).isEqualTo(Applied.DONE);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM p_ledger_transactions WHERE source_type = 'PAYOUT' AND source_id = ?",
                        Integer.class,
                        payoutId))
                .isEqualTo(1);

        mockMvc.perform(as(get("/api/admin/settlements/payouts/{id}", payoutId), MASTER, "MASTER"))
                .andExpect(jsonPath("$.data.current.status").value("DONE"))
                .andExpect(jsonPath("$.data.commands[0].commandId").value(commandId))
                .andExpect(jsonPath("$.data.commands[0].result").value("APPLIED"))
                .andExpect(jsonPath("$.data.commands[0].reason").value("ROOT_CAUSE_FIXED"))
                .andExpect(jsonPath("$.data.commands[0].actor").value("USER:" + MASTER));
    }

    @Test
    void sameCommandReplaysAndDifferentInputConflicts() throws Exception {
        long payoutId = failedPayout();
        long version = version(payoutId);
        String commandId = command();
        retry(payoutId, commandId, "원인 확인", version, 1, MASTER).andExpect(status().isOk());

        // 응답 유실 뒤 같은 명령 재전송: 현재 상태(READY·version+1)로 다시 판정하지 않고 최초 결과를 재현한다
        retry(payoutId, commandId, "원인 확인", version, 1, MASTER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("APPLIED"))
                .andExpect(jsonPath("$.data.version").value(version + 1));
        retry(payoutId, commandId, "다른 메모", version, 1, MASTER)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYOUT_COMMAND_CONFLICT"));
        retry(payoutId, commandId, "원인 확인", version, 1, MASTER + 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYOUT_COMMAND_CONFLICT"));
        assertThat(version(payoutId)).isEqualTo(version + 1);
    }

    @Test
    void staleOrNotFailedIsDeniedAndRecorded() throws Exception {
        long payoutId = failedPayout();
        long version = version(payoutId);

        retry(payoutId, command(), "옛 화면", version - 1, 1, MASTER)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
        assertThat(payout(payoutId)).containsEntry("status", "FAILED").containsEntry("version", version);

        long requested = readyPayout();
        assertThat(job.execute(requested)).contains(Applied.IN_PROGRESS);
        retry(requested, command(), "결과 불명 재요청 시도", version(requested), 1, MASTER)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
        assertThat(payout(requested)).containsEntry("status", "REQUESTED").containsEntry("attempt", 1);

        assertThat(jdbc.queryForList(
                        "SELECT result FROM p_payout_operation_commands WHERE payout_id IN (?, ?) ORDER BY processed_at",
                        String.class,
                        payoutId,
                        requested))
                .containsExactly("STALE", "DENIED");
    }

    @Test
    void concurrentCommandsTransitionOnce() throws Exception {
        long payoutId = failedPayout();
        long version = version(payoutId);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = List.of(
                    pool.submit(() -> {
                        start.await();
                        return retry(payoutId, command(), "동시 1", version, 1, MASTER)
                                .andReturn()
                                .getResponse()
                                .getStatus();
                    }),
                    pool.submit(() -> {
                        start.await();
                        return retry(payoutId, command(), "동시 2", version, 1, MASTER)
                                .andReturn()
                                .getResponse()
                                .getStatus();
                    }));
            start.countDown();
            assertThat(List.of(results.get(0).get(), results.get(1).get())).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(version(payoutId)).isEqualTo(version + 1);
    }

    /** retry 뒤 이전 attempt의 늦은 성공·실패 응답은 새 시도의 상태·다음 시각·분개를 바꾸지 못한다. */
    @Test
    void lateResultOfPreviousAttemptCannotOverwriteRetry() throws Exception {
        long payoutId = failedPayout();
        Map<String, Object> first = payout(payoutId);
        Claim oldClaim = new Claim(
                payoutId,
                1,
                (String) first.get("idempotency_key"),
                new PayoutAgency.PayoutOrder(
                        (String) first.get("idempotency_key"),
                        "seller:" + seller,
                        (String) first.get("account_ref"),
                        (String) first.get("holder_masked"),
                        43_400));
        retry(payoutId, command(), "원인 확인", version(payoutId), 1, MASTER).andExpect(status().isOk());
        assertThat(job.execute(payoutId)).contains(Applied.IN_PROGRESS);
        Map<String, Object> current = payout(payoutId);

        PayoutAgencyResult lateSuccess = new PayoutAgencyResult.Observed(
                AgencyStatus.COMPLETED,
                "po_old",
                oldClaim.idempotencyKey(),
                oldClaim.order().accountRef(),
                43_400L,
                null);
        assertThat(transactions.apply(oldClaim, lateSuccess)).isEqualTo(Applied.STALE);
        assertThat(transactions.apply(oldClaim, new PayoutAgencyResult.RequestInvalid("late")))
                .isEqualTo(Applied.STALE);

        assertThat(payout(payoutId))
                .containsEntry("status", "REQUESTED")
                .containsEntry("attempt", 2)
                .containsEntry("next_attempt_at", current.get("next_attempt_at"))
                .containsEntry("failure_code", null);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM p_ledger_transactions WHERE source_type = 'PAYOUT' AND source_id = ?",
                        Integer.class,
                        payoutId))
                .isZero();
    }

    @Test
    void listFiltersReceivablesAndRoleBoundary() throws Exception {
        long failed = failedPayout();
        Payment refunded = flow.approvedFor(seller, SCHEDULES.incrementAndGet());
        long schedule = jdbc.queryForObject(
                "SELECT schedule_id FROM p_settlement_items WHERE payment_id = ? AND kind = 'SALE'",
                Long.class,
                refunded.getId());
        statements.issue(seller, schedule, CUTOFF);
        refundService.refund(refunded.getOrderId(), RefundReason.SCHEDULE_CANCELED);

        mockMvc.perform(as(get("/api/admin/settlements/payouts"), MASTER, "MASTER")
                        .param("attention", "FAILED")
                        .param("after", String.valueOf(failed - 1))
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].payoutId").value(failed))
                .andExpect(jsonPath("$.data.items[0].attention").value("FAILED"));
        mockMvc.perform(as(get("/api/admin/settlements/receivables"), MASTER, "MASTER")
                        .param("after", String.valueOf(seller - 1))
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].sellerId").value(seller))
                .andExpect(jsonPath("$.data.items[0].receivable").value(43_400))
                .andExpect(jsonPath("$.data.items[0].openCount").value(1));

        mockMvc.perform(as(get("/api/admin/settlements/payouts"), seller, "SELLER"))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(post("/api/admin/settlements/payouts/{id}/retry", failed), MASTER, "USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        retry(failed, command(), " ", version(failed), 1, MASTER).andExpect(status().isBadRequest());
        mockMvc.perform(as(get("/api/admin/settlements/payouts/{id}", Long.MAX_VALUE), MASTER, "MASTER"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYOUT_NOT_FOUND"));
    }
}
