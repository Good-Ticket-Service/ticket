package com.sparta.ticket.payment.application.service.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.payment.application.dto.payment.CheckoutCommand;
import com.sparta.ticket.payment.application.dto.payment.ConfirmCommand;
import com.sparta.ticket.payment.application.dto.payment.RecoveryClaim;
import com.sparta.ticket.payment.domain.model.payment.Payment;
import com.sparta.ticket.payment.domain.model.payment.PaymentStatus;
import com.sparta.ticket.payment.domain.model.pg.PgInquiry;
import com.sparta.ticket.payment.domain.model.pg.PgProvider;
import com.sparta.ticket.payment.domain.model.refund.Refund;
import com.sparta.ticket.payment.domain.model.refund.RefundReason;
import com.sparta.ticket.payment.domain.model.refund.RefundStatus;
import com.sparta.ticket.payment.domain.repository.payment.PaymentRepository;
import com.sparta.ticket.payment.domain.repository.refund.RefundRepository;
import com.sparta.ticket.payment.support.PaymentIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class PaymentRecoveryIntegrationTest extends PaymentIntegrationTest {
    private static final AtomicLong IDS = new AtomicLong(7_000_000);

    @Autowired
    private PaymentService service;

    @Autowired
    private PaymentTransactions transactions;

    @Autowired
    private PaymentRecoveryTransactions recovery;

    @Autowired
    private PaymentRecoveryJob job;

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private RefundRepository refunds;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private PaymentRecoveryProperties properties;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private com.sparta.ticket.payment.application.service.pg.PgClientRegistry clients;

    @BeforeEach
    void isolateDatabase() {
        jdbc.update("DELETE FROM p_ledger_entries");
        jdbc.update("DELETE FROM p_ledger_transactions");
        jdbc.update("DELETE FROM p_settlement_statement_lines");
        jdbc.update("DELETE FROM p_payout_operation_commands");
        jdbc.update("DELETE FROM p_payouts");
        jdbc.update("DELETE FROM p_settlement_items");
        jdbc.update("DELETE FROM p_settlement_statements");
        jdbc.update("DELETE FROM p_refunds");
        jdbc.update("DELETE FROM p_payments");
    }

    private LocalDateTime now() {
        return LocalDateTime.now(paymentClock);
    }

    private Payment ready(int expirySeconds) {
        String order = "recovery-" + UUID.randomUUID();
        service.checkout(new CheckoutCommand(
                order,
                IDS.incrementAndGet(),
                1L,
                2L,
                10L,
                20L,
                PgProvider.TOSS,
                50_000,
                5_000,
                now().plusSeconds(expirySeconds)));
        return payments.findByOrderId(order).orElseThrow();
    }

    private Payment progress() {
        Payment payment = ready(600);
        transactions.requestConfirm(payment.getOrderId(), new ConfirmCommand("key-" + payment.getId(), 45_000));
        return transactions
                .markInProgress(payment.getId(), "key-" + payment.getId())
                .orElseThrow();
    }

    private Payment load(Payment payment) {
        return payments.findById(payment.getId()).orElseThrow();
    }

    private RecoveryClaim claim() {
        return recovery.claimOne().orElseThrow();
    }

    private PgInquiry.Approved approved(Payment payment, int amount) {
        return new PgInquiry.Approved(payment.getPgTransactionId(), amount, now());
    }

    private void waiting(Payment payment) {
        tx.executeWithoutResult(status -> {
            Payment locked = payments.lockById(payment.getId()).orElseThrow();
            refunds.saveAndFlush(Refund.waiting(locked, RefundReason.USER_CANCEL));
        });
    }

    @Test
    void readyExpiresAtBoundaryWithoutPgAndReceiptDoesNotPreventExpiry() {
        Payment payment = ready(10);
        transactions.requestConfirm(payment.getOrderId(), new ConfirmCommand("key", 45_000));
        paymentClock.advance(Duration.ofSeconds(9));
        job.runOnce();
        assertThat(load(payment).getStatus()).isEqualTo(PaymentStatus.READY);
        paymentClock.advance(Duration.ofSeconds(1));
        job.runOnce();
        assertThat(load(payment).getFailureCode()).isEqualTo("EXPIRED");
        assertThat(pg.inquiryCalls()).isZero();
        assertThat(pg.approveCalls()).isZero();
    }

    @Test
    void progressIsNotExpiredWithHoldTime() {
        Payment payment = progress();
        jdbc.update("UPDATE p_payments SET expires_at = ? WHERE id = ?", now().minusSeconds(1), payment.getId());
        paymentClock.advance(Duration.ofSeconds(10));
        pg.inquireReturns(request -> new PgInquiry.Unknown("조회 불명"));
        job.runOnce();
        assertThat(load(payment).getStatus()).isEqualTo(PaymentStatus.IN_PROGRESS);
        assertThat(pg.inquiryCalls()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void notFoundAndUnknownOnlyFailFromSixtySeconds(boolean notFound) {
        Payment payment = progress();
        waiting(payment);
        LocalDateTime approvalStarted = load(payment).getApproveRequestedAt();
        paymentClock.advance(Duration.ofSeconds(59));
        recovery.apply(claim(), notFound ? new PgInquiry.NotFound() : new PgInquiry.Unknown("secret"));
        assertThat(load(payment).getStatus()).isEqualTo(PaymentStatus.IN_PROGRESS);
        assertThat(load(payment).getApproveRequestedAt()).isEqualTo(approvalStarted);
        paymentClock.advance(Duration.ofSeconds(1));
        // 직전 예약이 미래여도 기존 점유값을 사용해 60초 경계의 판정 자체를 검증한다.
        Payment current = load(payment);
        RecoveryClaim boundary = new RecoveryClaim(
                current.getId(),
                current.getOrderId(),
                current.getPgProvider(),
                current.getPgTransactionId(),
                current.getNextAttemptAt());
        recovery.apply(boundary, notFound ? new PgInquiry.NotFound() : new PgInquiry.Unknown("secret"));
        assertThat(load(payment).getFailureCode()).isEqualTo(notFound ? "NOT_APPROVED" : "PG_UNRESOLVED");
        assertThat(refunds.findByPaymentId(payment.getId()).orElseThrow().getStatus())
                .isEqualTo(RefundStatus.SKIPPED);
    }

    @Test
    void inquiryOutsideTransactionSeesCommittedClaimAndRestoresApproval() {
        Payment payment = progress();
        paymentClock.advance(Duration.ofSeconds(10));
        pg.inquireReturns(request -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            assertThat(load(payment).getNextAttemptAt()).isAfter(now());
            assertThat(request.orderId()).isEqualTo(payment.getOrderId());
            return approved(payment, 45_000);
        });
        job.runOnce();
        assertThat(load(payment).getStatus()).isEqualTo(PaymentStatus.DONE);
        assertThat(pg.approveCalls()).isZero();
        job.runOnce();
        assertThat(pg.inquiryCalls()).isEqualTo(1);
    }

    @Test
    void canceledAndInvalidTransactionAreDistinguished() {
        Payment payment = progress();
        paymentClock.advance(Duration.ofSeconds(10));
        recovery.apply(claim(), new PgInquiry.Canceled("other-transaction", now()));
        assertThat(load(payment).getStatus()).isEqualTo(PaymentStatus.IN_PROGRESS);
        paymentClock.advance(Duration.ofSeconds(10));
        recovery.apply(claim(), new PgInquiry.Canceled(payment.getPgTransactionId(), now()));
        assertThat(load(payment).getFailureCode()).isEqualTo("CANCELED_AT_PG");
    }

    @Test
    void invalidApprovalDoesNotFinalizeOrRefundUnrelatedTransaction() {
        Payment payment = progress();
        paymentClock.advance(Duration.ofSeconds(10));
        recovery.apply(claim(), new PgInquiry.Approved("other-key", 45_000, now()));
        assertThat(load(payment).getStatus()).isEqualTo(PaymentStatus.IN_PROGRESS);
        assertThat(refunds.findByPaymentId(payment.getId())).isEmpty();
    }

    @Test
    void amountMismatchAndLateApprovalUseExistingRefundRules() {
        Payment payment = progress();
        paymentClock.advance(Duration.ofSeconds(60));
        RecoveryClaim old = claim();
        recovery.apply(old, new PgInquiry.Unknown("timeout"));
        assertThat(load(payment).getStatus()).isEqualTo(PaymentStatus.FAILED);
        recovery.apply(old, approved(payment, 46_000));
        recovery.apply(old, approved(payment, 46_000));
        Refund refund = refunds.findByPaymentId(payment.getId()).orElseThrow();
        assertThat(refund.getRefundAmount()).isEqualTo(46_000);
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(load(payment).getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void oldUnknownCannotChangeNewClaimAndOldApprovalIsStillApplied() {
        Payment payment = progress();
        paymentClock.advance(Duration.ofSeconds(10));
        RecoveryClaim first = claim();
        paymentClock.advance(Duration.ofSeconds(10));
        RecoveryClaim second = claim();
        recovery.apply(first, new PgInquiry.Unknown("old"));
        assertThat(load(payment).getNextAttemptAt()).isEqualTo(second.claimedUntil());
        assertThat(load(payment).getRecoveryAttempts()).isZero();
        recovery.apply(first, approved(payment, 45_000));
        assertThat(load(payment).getStatus()).isEqualTo(PaymentStatus.DONE);
        recovery.apply(second, new PgInquiry.NotFound());
        assertThat(load(payment).getStatus()).isEqualTo(PaymentStatus.DONE);
    }

    @Test
    void twoClaimersDoNotReceiveSameRowBeforeLeaseExpires() throws Exception {
        progress();
        paymentClock.advance(Duration.ofSeconds(10));
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> {
                return recovery.claimOne();
            });
            var b = pool.submit(() -> {
                return recovery.claimOne();
            });
            long claimed = java.util.stream.Stream.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS))
                    .filter(java.util.Optional::isPresent)
                    .count();
            assertThat(claimed).isEqualTo(1);
            paymentClock.advance(Duration.ofSeconds(10));
            assertThat(recovery.claimOne()).isPresent();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void lockedOldestCandidateDoesNotBlockNextCandidate() throws Exception {
        Payment first = progress();
        Payment second = progress();
        paymentClock.advance(Duration.ofSeconds(10));
        var pool = Executors.newSingleThreadExecutor();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            var blocker = pool.submit(() -> tx.executeWithoutResult(status -> {
                payments.lockById(first.getId()).orElseThrow();
                held.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("잠금 대기 초과");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(claim().paymentId()).isEqualTo(second.getId());
            release.countDown();
            blocker.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void applyFailureKeepsClaimForRetryAndOtherCandidatesContinue() {
        Payment first = progress();
        Payment second = progress();
        paymentClock.advance(Duration.ofSeconds(10));
        pg.inquireReturns(request -> {
            publisher.fail = request.orderId().equals(first.getOrderId());
            if (publisher.fail) paymentClock.advance(Duration.ofSeconds(12));
            Payment p = request.orderId().equals(first.getOrderId()) ? first : second;
            return approved(p, 45_000);
        });
        job.runOnce();
        assertThat(load(first).getStatus()).isEqualTo(PaymentStatus.IN_PROGRESS);
        assertThat(load(second).getStatus()).isEqualTo(PaymentStatus.DONE);
        assertThat(pg.inquiryCalls()).isEqualTo(2);
        publisher.fail = false;
        pg.inquireReturns(request -> approved(first, 45_000));
        paymentClock.advance(Duration.ofSeconds(10));
        job.runOnce();
        assertThat(load(first).getStatus()).isEqualTo(PaymentStatus.DONE);
    }

    @Test
    void queryExceptionDoesNotStopOtherCandidatesOrLeakMessage() {
        Payment first = progress();
        Payment second = progress();
        paymentClock.advance(Duration.ofSeconds(10));
        pg.inquireReturns(request -> {
            if (request.orderId().equals(first.getOrderId())) throw new IllegalStateException("AUTH_SECRET");
            return approved(second, 45_000);
        });
        job.runOnce();
        assertThat(load(first).getStatus()).isEqualTo(PaymentStatus.IN_PROGRESS);
        assertThat(load(first).getLastError()).doesNotContain("AUTH_SECRET");
        assertThat(load(second).getStatus()).isEqualTo(PaymentStatus.DONE);
    }

    @Test
    void expiredReadySaveFailureDoesNotStarveNextCandidate() {
        Payment first = ready(1);
        Payment second = ready(1);
        paymentClock.advance(Duration.ofSeconds(1));
        publisher.fail = true;
        List<Long> attempted = new ArrayList<>(List.of(0L));
        assertThatThrownBy(() -> recovery.expireOne(attempted)).isInstanceOf(RuntimeException.class);
        publisher.fail = false;
        assertThat(recovery.expireOne(attempted)).isTrue();
        assertThat(load(first).getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(load(second).getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void timeBudgetDoesNotClaimUnexecutedBatchAndRecordsWaitingAge() {
        Payment first = progress();
        Payment second = progress();
        paymentClock.advance(Duration.ofSeconds(10));
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var bounded =
                new PaymentRecoveryJob(recovery, clients, new PaymentRecoveryProperties(50, 10, 60, 60, 1), registry);
        pg.inquireReturns(request -> {
            paymentClock.advance(Duration.ofSeconds(5));
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1100));
            return approved(first, 45_000);
        });
        bounded.runOnce();
        assertThat(load(first).getStatus()).isEqualTo(PaymentStatus.DONE);
        assertThat(load(second).getNextAttemptAt()).isEqualTo(second.getNextAttemptAt());
        assertThat(pg.inquiryCalls()).isEqualTo(1);
        assertThat(registry.get("payment.recovery.oldest.due.age.seconds")
                        .gauge()
                        .value())
                .isGreaterThan(0);
    }

    @Test
    void jobRecoversActualHttpApprovalWithLostResponseWithoutReapproval() {
        String base = com.sparta.ticket.payment.support.pg.FakePgContainer.baseUrl();
        var admin = new com.sparta.ticket.payment.support.pg.FakePgAdmin(base);
        admin.clearFaults();
        Payment payment = ready(600);
        String token = admin.tossCheckout(payment.getOrderId(), 45_000);
        var props = new com.sparta.ticket.payment.infrastructure.pg.PgProperties(
                Duration.ofSeconds(2),
                Duration.ofSeconds(1),
                new com.sparta.ticket.payment.infrastructure.pg.PgProperties.Toss(base, "test_sk_fake_toss"),
                new com.sparta.ticket.payment.infrastructure.pg.PgProperties.Kakao(
                        base,
                        "DEV_fake_kakao",
                        "TC0ONETIME",
                        "http://localhost/approve",
                        "http://localhost/cancel",
                        "http://localhost/fail"));
        var http = new com.sparta.ticket.payment.infrastructure.pg.toss.TossPgClient(
                org.springframework.web.client.RestClient.builder(),
                props,
                new com.fasterxml.jackson.databind.ObjectMapper());
        pg.approveReturns(http::approve);
        pg.inquireReturns(http::inquire);
        try {
            admin.fault("TOSS", "CONFIRM", "{\"responseLossRate\":1,\"holdMs\":3000}");
            assertThat(service.confirm(payment.getOrderId(), new ConfirmCommand(token, 45_000))
                            .status())
                    .isEqualTo(PaymentStatus.IN_PROGRESS);
            assertThat(admin.transactionStatus(payment.getOrderId())).isEqualTo("DONE");
            admin.clearFaults();
            paymentClock.advance(Duration.ofSeconds(10));
            job.runOnce();
            assertThat(load(payment).getStatus()).isEqualTo(PaymentStatus.DONE);
            assertThat(pg.approveCalls()).isEqualTo(1);
            assertThat(pg.inquiryCalls()).isEqualTo(1);
        } finally {
            admin.clearFaults();
        }
    }

    @Test
    void unresolvedCounterOnlyIncrementsAfterCommit() {
        Payment payment = progress();
        paymentClock.advance(Duration.ofSeconds(60));
        RecoveryClaim claim = claim();
        double before = meters.counter("payment.unresolved").count();
        publisher.fail = true;
        assertThatThrownBy(() -> recovery.apply(claim, new PgInquiry.Unknown("timeout")))
                .isInstanceOf(RuntimeException.class);
        assertThat(meters.counter("payment.unresolved").count()).isEqualTo(before);
        publisher.fail = false;
        recovery.apply(claim, new PgInquiry.Unknown("timeout"));
        assertThat(meters.counter("payment.unresolved").count()).isEqualTo(before + 1);
    }
}
