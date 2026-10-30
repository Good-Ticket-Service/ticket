package com.sparta.ticket.payment.application.service.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.payment.application.port.settlement.PayoutAgency;
import com.sparta.ticket.payment.application.port.settlement.PayoutAgency.AgencyStatus;
import com.sparta.ticket.payment.application.port.settlement.PayoutAgency.PayoutAgencyResult;
import com.sparta.ticket.payment.application.service.payment.PaymentService;
import com.sparta.ticket.payment.application.service.payment.PaymentTransactions;
import com.sparta.ticket.payment.application.service.settlement.PayoutExecutionTransactions.Applied;
import com.sparta.ticket.payment.domain.repository.payment.PaymentRepository;
import com.sparta.ticket.payment.domain.repository.settlement.PayoutRepository;
import com.sparta.ticket.payment.infrastructure.pg.PgProperties;
import com.sparta.ticket.payment.infrastructure.pg.payout.PgPayoutAgency;
import com.sparta.ticket.payment.support.PaymentIntegrationTest;
import com.sparta.ticket.payment.support.SettlementPaymentFlow;
import com.sparta.ticket.payment.support.pg.FakePgAdmin;
import com.sparta.ticket.payment.support.pg.FakePgContainer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

/**
 * REQUESTED 지급 recovery(Task 20-9, settlement-design 7.2절, 시험 S6·S9). 같은 키 조회·조건부 재전송·상태별 조회 일정과,
 * 응답 유실·5xx·호출 전 중단을 섞어도 전부 수렴하고 중복 송금이 없다는 불변식을 fake-pg 실제 HTTP로 확인한다.
 * Note: 다른 시험의 REQUESTED 지급을 건드리지 않도록 묶음 실행(recoverOnce) 대신 지급 하나씩 recover한다.
 */
class PayoutRecoveryIntegrationTest extends PaymentIntegrationTest {

    private static final LocalDateTime CUTOFF = LocalDateTime.of(2026, 11, 12, 4, 0);
    private static final AtomicLong SELLERS = new AtomicLong(270_000);
    private static final AtomicLong SCHEDULES = new AtomicLong(280_000);

    private final FakePgAdmin admin = new FakePgAdmin(FakePgContainer.baseUrl());

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentTransactions paymentTransactions;

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
    private PayoutAgency real;
    private CountingAgency agency;
    private PayoutExecutionJob job;

    @BeforeEach
    void setUp() {
        admin.clearFaults();
        flow = new SettlementPaymentFlow(paymentService, paymentTransactions, payments, jdbc, paymentClock);
        real = new PgPayoutAgency(RestClient.builder(), properties(), objectMapper);
        agency = new CountingAgency(real);
        job = jobWith(agency, true, 168);
    }

    @AfterEach
    void clearFaults() {
        admin.clearFaults();
    }

    private PayoutExecutionJob jobWith(PayoutAgency payoutAgency, boolean enabled, long keyValidityHours) {
        return new PayoutExecutionJob(
                payouts,
                transactions,
                payoutAgency,
                new SimpleMeterRegistry(),
                paymentClock,
                enabled,
                10,
                keyValidityHours);
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

    /** 판매자 하나·결제 하나(판매자 몫 43,400)로 정산서를 발행하고, 계좌를 등록해 지금 실행할 수 있는 READY 지급을 만든다. */
    private long readyPayout() {
        long seller = SELLERS.incrementAndGet();
        flow.approvedFor(seller, SCHEDULES.incrementAndGet());
        statements.issue(
                seller,
                jdbc.queryForObject(
                        "SELECT schedule_id FROM p_settlement_items WHERE seller_id = ?", Long.class, seller),
                CUTOFF);
        jdbc.update(
                "UPDATE p_seller_payout_accounts SET bank_code = '004', account_ref = ?, account_masked = '****1234',"
                        + " holder_masked = '홍*동', version = version + 1 WHERE seller_id = ?",
                "acct_" + seller,
                seller);
        long payoutId = jdbc.queryForObject("SELECT id FROM p_payouts WHERE seller_id = ?", Long.class, seller);
        jdbc.update(
                "UPDATE p_payouts SET next_attempt_at = ? WHERE id = ?",
                LocalDateTime.now(paymentClock).minusMinutes(1),
                payoutId);
        return payoutId;
    }

    private Optional<Applied> recoverNow(PayoutExecutionJob target, long payoutId) {
        jdbc.update(
                "UPDATE p_payouts SET next_attempt_at = ? WHERE id = ? AND status = 'REQUESTED'",
                LocalDateTime.now(paymentClock).minusSeconds(1),
                payoutId);
        return target.recover(payoutId);
    }

    private Applied recoverUntilSettled(long payoutId) throws InterruptedException {
        Applied applied = null;
        for (int i = 0; i < 30; i++) {
            Thread.sleep(100);
            applied = recoverNow(job, payoutId).orElseThrow();
            if (applied != Applied.IN_PROGRESS) return applied;
        }
        return applied;
    }

    private Map<String, Object> payout(long payoutId) {
        return jdbc.queryForMap("SELECT * FROM p_payouts WHERE id = ?", payoutId);
    }

    private LocalDateTime nextAttemptAt(long payoutId) {
        return ((Timestamp) payout(payoutId).get("next_attempt_at")).toLocalDateTime();
    }

    @Test
    void stopBeforeRequestIsResentWithSameKeyAndCompletes() throws InterruptedException {
        long payoutId = readyPayout();
        // 실행권만 커밋하고 지급대행을 부르기 전에 끊긴 상황
        transactions.claim(payoutId).orElseThrow();

        assertThat(recoverNow(job, payoutId)).contains(Applied.IN_PROGRESS);

        assertThat(agency.requests.get()).isEqualTo(1);
        assertThat(payout(payoutId))
                .containsEntry("attempt", 1)
                .containsEntry("provider_status", "ACCEPTED")
                .containsEntry("idempotency_key", "payout:" + payoutId + ":1");
        assertThat(recoverUntilSettled(payoutId)).isEqualTo(Applied.DONE);
        assertThat(agency.requests.get()).isEqualTo(1);
    }

    @Test
    void notFoundIsNotResentWhenSwitchOffOrKeyExpired() {
        long disabled = readyPayout();
        transactions.claim(disabled).orElseThrow();
        assertThat(recoverNow(jobWith(agency, false, 168), disabled)).contains(Applied.UNKNOWN);
        assertThat(payout(disabled))
                .containsEntry("status", "REQUESTED")
                .containsEntry("last_error", "PROVIDER_NOT_FOUND");

        long expired = readyPayout();
        transactions.claim(expired).orElseThrow();
        jdbc.update(
                "UPDATE p_payouts SET requested_at = ? WHERE id = ?",
                LocalDateTime.now(paymentClock).minusHours(200),
                expired);
        assertThat(recoverNow(job, expired)).contains(Applied.UNKNOWN);

        assertThat(agency.requests.get()).isZero();
    }

    @Test
    void inquiryOnlyIsNeverResentEvenIfNotFound() {
        long payoutId = readyPayout();
        PayoutAgency conflicting = new CountingAgency(real) {
            @Override
            public PayoutAgencyResult request(PayoutOrder order) {
                return new PayoutAgencyResult.KeyConflict("422");
            }
        };
        assertThat(jobWith(conflicting, true, 168).execute(payoutId)).contains(Applied.INQUIRY_ONLY);

        // fake-pg는 이 키를 받은 적이 없어 404지만, inquiry_only라 재전송하지 않는다
        assertThat(recoverNow(job, payoutId)).contains(Applied.UNKNOWN);
        assertThat(agency.requests.get()).isZero();
        assertThat(payout(payoutId)).containsEntry("inquiry_only", true).containsEntry("status", "REQUESTED");
    }

    @Test
    void acceptedPayoutKeepsStatusAndLongScheduleWhenInquiryFails() {
        long payoutId = readyPayout();
        assertThat(job.execute(payoutId)).contains(Applied.IN_PROGRESS);
        LocalDateTime requestedAt = ((Timestamp) payout(payoutId).get("requested_at")).toLocalDateTime();
        // 접수 확인 뒤 첫 조회는 접수 + 예상 처리 시간(1시간)
        assertThat(nextAttemptAt(payoutId)).isEqualTo(requestedAt.plusMinutes(60));

        admin.fault("TOSS", "PAYOUT_INQUIRE", "{\"serverErrorRate\":1}");
        assertThat(recoverNow(job, payoutId)).contains(Applied.UNKNOWN);

        assertThat(payout(payoutId)).containsEntry("provider_status", "ACCEPTED");
        assertThat(payout(payoutId).get("last_error")).asString().contains("500");
        assertThat(nextAttemptAt(payoutId)).isEqualTo(requestedAt.plusMinutes(60));
    }

    @Test
    void notFoundAfterAcceptedBlocksToInquiryOnly() {
        long payoutId = readyPayout();
        assertThat(job.execute(payoutId)).contains(Applied.IN_PROGRESS);
        PayoutAgency forgetful = new CountingAgency(real) {
            @Override
            public PayoutAgencyResult inquire(String idempotencyKey) {
                return new PayoutAgencyResult.NotFound();
            }
        };

        assertThat(recoverNow(jobWith(forgetful, true, 168), payoutId)).contains(Applied.INQUIRY_ONLY);

        assertThat(payout(payoutId))
                .containsEntry("inquiry_only", true)
                .containsEntry("verification_reason", "NOT_FOUND_AFTER_ACCEPTED");
        assertThat(agency.requests.get()).isEqualTo(1);
    }

    @Test
    void unknownResultUsesShortBackoff() {
        long payoutId = readyPayout();
        admin.fault("TOSS", "PAYOUT", "{\"serverErrorRate\":1}");

        assertThat(job.execute(payoutId)).contains(Applied.UNKNOWN);

        LocalDateTime now = LocalDateTime.now(paymentClock);
        assertThat(nextAttemptAt(payoutId)).isBetween(now.plusSeconds(20), now.plusSeconds(40));
        assertThat(payout(payoutId)).containsEntry("provider_status", "UNKNOWN");
    }

    /**
     * 수렴 불변식. 요청 5xx·응답 유실·조회 5xx를 섞고 일부는 실행권만 얻고 끊겨도, recovery만으로 전부 DONE이 되고 지급마다 attempt 1·
     * 키 1개라 지급대행 쪽 지급도 1건이며(같은 키 멱등), 지급 분개 합이 지급액 합과 같다.
     */
    @Test
    void mixedFailuresConvergeWithoutDuplicatePayout() throws InterruptedException {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 8; i++) ids.add(readyPayout());
        admin.fault("TOSS", "PAYOUT", "{\"serverErrorRate\":0.3,\"responseLossRate\":0.3,\"holdMs\":1100}");
        admin.fault("TOSS", "PAYOUT_INQUIRE", "{\"serverErrorRate\":0.3}");

        for (int i = 0; i < ids.size(); i++) {
            if (i % 4 == 0) transactions.claim(ids.get(i)).orElseThrow();
            else job.execute(ids.get(i));
        }
        for (int round = 0; round < 60 && !allDone(ids); round++) {
            Thread.sleep(150);
            for (Long id : ids) recoverNow(job, id);
        }

        assertThat(ids)
                .allSatisfy(id ->
                        assertThat(payout(id)).containsEntry("status", "DONE").containsEntry("attempt", 1));
        admin.clearFaults();
        for (Long id : ids) {
            Map<String, Object> row = payout(id);
            PayoutAgencyResult provider = real.inquire((String) row.get("idempotency_key"));
            assertThat(provider).isInstanceOfSatisfying(PayoutAgencyResult.Observed.class, observed -> {
                assertThat(observed.status()).isEqualTo(AgencyStatus.COMPLETED);
                assertThat(observed.payoutId()).isEqualTo(row.get("external_payout_id"));
            });
        }
        Map<String, Object> journals = jdbc.queryForMap(
                "SELECT count(DISTINCT t.id) AS journals, sum(e.debit) AS debit FROM p_ledger_transactions t"
                        + " JOIN p_ledger_entries e ON e.transaction_id = t.id"
                        + " WHERE t.source_type = 'PAYOUT' AND t.source_id IN ("
                        + String.join(",", ids.stream().map(String::valueOf).toList()) + ")");
        assertThat(journals).containsEntry("journals", 8L).containsEntry("debit", BigDecimal.valueOf(8 * 43_400L));
    }

    private boolean allDone(List<Long> ids) {
        return ids.stream().allMatch(id -> "DONE".equals(payout(id).get("status")));
    }

    /** 실제 어댑터를 감싸 지급 요청(최초·재전송) 횟수를 센다. */
    private static class CountingAgency implements PayoutAgency {
        private final PayoutAgency delegate;
        final AtomicInteger requests = new AtomicInteger();

        CountingAgency(PayoutAgency delegate) {
            this.delegate = delegate;
        }

        @Override
        public PayoutAgencyResult request(PayoutOrder order) {
            requests.incrementAndGet();
            return delegate.request(order);
        }

        @Override
        public PayoutAgencyResult inquire(String idempotencyKey) {
            return delegate.inquire(idempotencyKey);
        }
    }
}
