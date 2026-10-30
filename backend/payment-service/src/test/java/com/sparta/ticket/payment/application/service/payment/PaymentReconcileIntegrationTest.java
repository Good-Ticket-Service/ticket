package com.sparta.ticket.payment.application.service.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.payment.application.dto.payment.CheckoutCommand;
import com.sparta.ticket.payment.application.dto.payment.ConfirmCommand;
import com.sparta.ticket.payment.application.dto.payment.ReconcileClaim;
import com.sparta.ticket.payment.domain.model.payment.Payment;
import com.sparta.ticket.payment.domain.model.payment.PaymentFailureCode;
import com.sparta.ticket.payment.domain.model.payment.PaymentStatus;
import com.sparta.ticket.payment.domain.model.pg.PgInquiry;
import com.sparta.ticket.payment.domain.model.pg.PgProvider;
import com.sparta.ticket.payment.domain.model.pg.PgResult;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class PaymentReconcileIntegrationTest extends PaymentIntegrationTest {
    @Autowired
    private com.sparta.ticket.payment.application.service.refund.RefundRecoveryJob refundRecovery;

    private static final AtomicLong IDS = new AtomicLong(8_000_000);

    @Autowired
    private PaymentService service;

    @Autowired
    private PaymentTransactions transactions;

    @Autowired
    private PaymentReconcileTransactions reconcile;

    @Autowired
    private PaymentReconcileJob job;

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private RefundRepository refunds;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private MeterRegistry meters;

    @BeforeEach
    void isolateDatabase() {
        jdbc.update("DELETE FROM p_ledger_entries");
        jdbc.update("DELETE FROM p_ledger_transactions");
        jdbc.update("DELETE FROM p_settlement_items");
        jdbc.update("DELETE FROM p_refunds");
        jdbc.update("DELETE FROM p_payments");
    }

    private LocalDateTime now() {
        return LocalDateTime.now(paymentClock);
    }

    private Payment ready(long reservationId) {
        String order = "reconcile-" + UUID.randomUUID();
        service.checkout(new CheckoutCommand(
                order, reservationId, 1L, 2L, 10L, 20L, PgProvider.TOSS, 50_000, 5_000, now().plusMinutes(10)));
        return payments.findByOrderId(order).orElseThrow();
    }

    private Payment progress(long reservationId) {
        Payment payment = ready(reservationId);
        transactions.requestConfirm(payment.getOrderId(), new ConfirmCommand("key-" + payment.getId(), 45_000));
        return transactions
                .markInProgress(payment.getId(), "key-" + payment.getId())
                .orElseThrow();
    }

    private Payment failed() {
        return fail(progress(IDS.incrementAndGet()));
    }

    private Payment fail(Payment payment) {
        tx.executeWithoutResult(status -> transactions.failForRecovery(
                payments.lockById(payment.getId()).orElseThrow(), PaymentFailureCode.PG_UNRESOLVED));
        return load(payment);
    }

    private Payment load(Payment payment) {
        return payments.findById(payment.getId()).orElseThrow();
    }

    private ReconcileClaim claim() {
        return reconcile.claimOne(new ArrayList<>(List.of(0L))).orElseThrow();
    }

    private PgInquiry.Approved approved(Payment payment, int amount) {
        return new PgInquiry.Approved(payment.getPgTransactionId(), amount, now());
    }

    @Test
    void allFiveCheckpointsUseOriginalFailureTimeAndExactBoundaries() {
        Payment payment = failed();
        LocalDateTime failure = payment.getFailedAt();
        pg.inquireReturns(request -> new PgInquiry.NotFound());
        List<Long> schedule = List.of(1L, 5L, 15L, 60L, 1440L);
        for (int i = 0; i < schedule.size(); i++) {
            LocalDateTime due = failure.plusMinutes(schedule.get(i));
            assertThat(load(payment).getNextReconcileAt()).isEqualTo(due);
            paymentClock.advance(Duration.between(now(), due.minusNanos(1000)));
            job.runOnce();
            assertThat(pg.inquiryCalls()).isEqualTo(i);
            paymentClock.advance(Duration.ofNanos(1000));
            job.runOnce();
            assertThat(load(payment).getReconcileCount()).isEqualTo(i + 1);
            assertThat(load(payment).getFailedAt()).isEqualTo(failure);
        }
        assertThat(load(payment).getNextReconcileAt()).isNull();
        job.runOnce();
        assertThat(pg.inquiryCalls()).isEqualTo(5);
        assertThat(pg.approveCalls()).isZero();
    }

    @Test
    void withoutApprovalAndOtherStatusesAreNotCandidates() {
        Payment ready = ready(IDS.incrementAndGet());
        fail(ready);
        progress(IDS.incrementAndGet());
        Payment done = progress(IDS.incrementAndGet());
        transactions.complete(done.getId(), new PgResult.Approved(done.getPgTransactionId(), 45_000, now()));
        paymentClock.advance(Duration.ofDays(2));
        job.runOnce();
        assertThat(pg.inquiryCalls()).isZero();
        assertThat(load(ready).getNextReconcileAt()).isNull();
    }

    @Test
    void unknownRetriesSameCheckpointAndLongUnknownIsObservable() {
        Payment payment = failed();
        LocalDateTime failure = payment.getFailedAt();
        paymentClock.advance(Duration.ofMinutes(1));
        pg.inquireReturns(request -> {
            throw new IllegalStateException("AUTH_SECRET");
        });
        job.runOnce();
        assertThat(load(payment).getReconcileCount()).isZero();
        assertThat(load(payment).getNextReconcileAt()).isAfter(now());
        paymentClock.advance(Duration.ofDays(1));
        double before = meters.counter("payment.reconcile.long.unknown").count();
        job.runOnce();
        assertThat(meters.counter("payment.reconcile.long.unknown").count()).isEqualTo(before + 1);
        assertThat(load(payment).getFailedAt()).isEqualTo(failure);
        assertThat(load(payment).getReconcileCount()).isZero();
        paymentClock.advance(Duration.ofMinutes(5));
        pg.inquireReturns(request -> new PgInquiry.NotFound());
        job.runOnce();
        // 장기 중단 뒤에도 한 번의 조회로 두 체크포인트를 소비하지 않는다.
        assertThat(load(payment).getReconcileCount()).isEqualTo(1);
        assertThat(load(payment).getNextReconcileAt()).isEqualTo(failure.plusMinutes(5));
    }

    @Test
    void persistedUnknownBackoffSurvivesReclaimAndResetsOnlyOnConfirmedResult() {
        Payment payment = failed();
        paymentClock.advance(Duration.ofMinutes(1));
        LocalDateTime since = now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        long[] base = {30, 60, 120, 240, 300, 300};
        for (int i = 0; i < base.length; i++) {
            LocalDateTime started = now();
            job.runOnce();
            Payment stored = load(payment);
            assertThat(stored.getReconcileCount()).isZero();
            assertThat(stored.getReconcileRetryCount()).isEqualTo(i + 1);
            assertThat(stored.getReconcileUnknownSince()).isEqualTo(since);
            long delay = Duration.between(
                            started.truncatedTo(java.time.temporal.ChronoUnit.MICROS), stored.getNextReconcileAt())
                    .toMillis();
            assertThat(delay).isBetween((long) (base[i] * 800), Math.min(300_000, (long) (base[i] * 1200)));
            paymentClock.advance(
                    Duration.between(now(), stored.getNextReconcileAt().minusNanos(1000)));
            job.runOnce();
            assertThat(pg.inquiryCalls()).isEqualTo(i + 1);
            paymentClock.advance(Duration.ofNanos(1000));
        }
        pg.inquireReturns(request -> new PgInquiry.NotFound());
        job.runOnce();
        Payment stored = load(payment);
        assertThat(stored.getReconcileCount()).isEqualTo(1);
        assertThat(stored.getNextReconcileAt()).isEqualTo(payment.getFailedAt().plusMinutes(5));
        assertThat(stored.getReconcileRetryCount()).isZero();
        assertThat(stored.getReconcileUnknownSince()).isNull();
    }

    @ParameterizedTest
    @CsvSource({"299,false,false", "300,true,false", "899,true,false", "900,false,true"})
    void alertBoundariesUseFirstUnknownRatherThanFailureTime(long seconds, boolean warning, boolean critical) {
        Payment payment = failed();
        // 오래 대기한 거래라도 첫 Unknown 자체를 장기 불명으로 세지 않는다.
        paymentClock.advance(Duration.ofDays(2));
        double beforeWarning =
                meters.counter("payment.reconcile.unknown.warning").count();
        double beforeCritical =
                meters.counter("payment.reconcile.unknown.critical").count();
        job.runOnce();
        assertThat(meters.counter("payment.reconcile.unknown.warning").count()).isEqualTo(beforeWarning);
        assertThat(meters.counter("payment.reconcile.unknown.critical").count()).isEqualTo(beforeCritical);
        paymentClock.advance(Duration.ofSeconds(seconds));
        job.runOnce();
        assertThat(meters.get("payment.reconcile.oldest.unknown.age.seconds")
                        .gauge()
                        .value())
                .isEqualTo((double) seconds);
        assertThat(load(payment).getReconcileCount()).isZero();
        assertThat(meters.counter("payment.reconcile.unknown.warning").count())
                .isEqualTo(beforeWarning + (warning ? 1 : 0));
        assertThat(meters.counter("payment.reconcile.unknown.critical").count())
                .isEqualTo(beforeCritical + (critical ? 1 : 0));
    }

    @Test
    void snapshotPreservesExistingScheduleAndRepeatedFailureRecordingCannotMoveBaseTime() {
        Payment payment = progress(IDS.incrementAndGet());
        tx.executeWithoutResult(status -> {
            Payment locked = payments.lockById(payment.getId()).orElseThrow();
            locked.fail(PaymentFailureCode.PG_UNRESOLVED, "timeout");
            locked.recordFailure(
                    now(), new com.sparta.ticket.payment.domain.model.payment.ReconcileSchedule(List.of(2L, 9L)));
            locked.recordFailure(
                    now().plusHours(1),
                    new com.sparta.ticket.payment.domain.model.payment.ReconcileSchedule(List.of(1L, 5L)));
        });
        LocalDateTime failure = load(payment).getFailedAt();
        assertThat(load(payment).getNextReconcileAt()).isEqualTo(failure.plusMinutes(2));
        paymentClock.advance(Duration.ofMinutes(2));
        pg.inquireReturns(request -> new PgInquiry.NotFound());
        job.runOnce();
        assertThat(load(payment).getNextReconcileAt()).isEqualTo(failure.plusMinutes(9));
        assertThat(load(payment).getReconcileCheckpointsMinutes()).isEqualTo("2,9");
    }

    @Test
    void staleUnknownDoesNotOverwriteReclaimedRetryState() {
        Payment payment = failed();
        paymentClock.advance(Duration.ofMinutes(1));
        ReconcileClaim old = claim();
        paymentClock.advance(Duration.ofSeconds(30));
        ReconcileClaim current = claim();
        reconcile.apply(current, new PgInquiry.Unknown("unavailable"));
        Payment before = load(payment);
        reconcile.apply(old, new PgInquiry.Unknown("old-response"));
        Payment after = load(payment);
        assertThat(after.getReconcileRetryCount()).isEqualTo(1);
        assertThat(after.getReconcileUnknownSince()).isEqualTo(before.getReconcileUnknownSince());
        assertThat(after.getNextReconcileAt()).isEqualTo(before.getNextReconcileAt());
    }

    @Test
    void finalCheckpointUnknownRemainsPendingUntilConfirmed() {
        Payment payment = failed();
        pg.inquireReturns(request -> new PgInquiry.NotFound());
        for (int i = 0; i < 4; i++) {
            paymentClock.advance(Duration.between(now(), load(payment).getNextReconcileAt()));
            job.runOnce();
        }
        paymentClock.advance(Duration.between(now(), load(payment).getNextReconcileAt()));
        pg.inquireReturns(request -> new PgInquiry.Unknown("unavailable"));
        job.runOnce();
        assertThat(load(payment).getReconcileCount()).isEqualTo(4);
        assertThat(load(payment).getReconcileRetryCount()).isEqualTo(1);
        assertThat(load(payment).getNextReconcileAt()).isNotNull();
        paymentClock.advance(Duration.between(now(), load(payment).getNextReconcileAt()));
        pg.inquireReturns(request -> new PgInquiry.NotFound());
        job.runOnce();
        assertThat(load(payment).getReconcileCount()).isEqualTo(5);
        assertThat(load(payment).getReconcileRetryCount()).isZero();
        assertThat(load(payment).getReconcileUnknownSince()).isNull();
        assertThat(load(payment).getNextReconcileAt()).isNull();
    }

    @Test
    void failedUnknownSaveDoesNotPersistRetryOrPublishWarning() {
        Payment payment = failed();
        paymentClock.advance(Duration.ofMinutes(1));
        ReconcileClaim claim = claim();
        double before = meters.counter("payment.reconcile.unknown").count();
        jdbc.execute("ALTER TABLE p_payments ADD CONSTRAINT ck_reconcile_unknown_test CHECK (reconcile_retry_count=0)");
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> reconcile.apply(claim, new PgInquiry.Unknown("unavailable")))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("ALTER TABLE p_payments DROP CONSTRAINT ck_reconcile_unknown_test");
        }
        assertThat(load(payment).getReconcileRetryCount()).isZero();
        assertThat(load(payment).getReconcileUnknownSince()).isNull();
        assertThat(meters.counter("payment.reconcile.unknown").count()).isEqualTo(before);
    }

    @Test
    void invalidForeignApprovalDoesNotAdvanceOrRefund() {
        Payment payment = failed();
        paymentClock.advance(Duration.ofMinutes(1));
        pg.inquireReturns(request -> new PgInquiry.Approved("foreign-id", 45_000, now()));
        job.runOnce();
        assertThat(load(payment).getReconcileCount()).isZero();
        assertThat(refunds.findByPaymentId(payment.getId())).isEmpty();
        paymentClock.advance(Duration.ofMinutes(1));
        pg.inquireReturns(request -> new PgInquiry.Canceled("foreign-id", now()));
        job.runOnce();
        assertThat(load(payment).getReconcileCount()).isZero();
    }

    @Test
    void canceledIdentityConfirmedAdvancesWithoutNewRefund() {
        Payment payment = failed();
        paymentClock.advance(Duration.ofMinutes(1));
        pg.inquireReturns(request -> new PgInquiry.Canceled(payment.getPgTransactionId(), now()));
        job.runOnce();
        assertThat(load(payment).getReconcileCount()).isEqualTo(1);
        assertThat(refunds.findByPaymentId(payment.getId())).isEmpty();
    }

    @Test
    void lateOldApprovalRefundsOnlyOldAttemptAndKeepsNewPayment() {
        long reservation = IDS.incrementAndGet();
        Payment old = fail(progress(reservation));
        LocalDateTime originalFailure = old.getFailedAt();
        Payment current = progress(reservation);
        transactions.complete(current.getId(), new PgResult.Approved(current.getPgTransactionId(), 45_000, now()));
        paymentClock.advance(Duration.ofMinutes(1));
        pg.inquireReturns(request -> approved(old, 46_000));
        job.runOnce();
        Refund refund = refunds.findByPaymentId(old.getId()).orElseThrow();
        assertThat(refund.getRefundAmount()).isEqualTo(46_000);
        assertThat(refund.getReason()).isEqualTo(RefundReason.AUTO_VOID);
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(load(old).getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(load(old).getFailedAt()).isEqualTo(originalFailure);
        assertThat(load(current).getStatus()).isEqualTo(PaymentStatus.DONE);
        assertThat(refunds.findByPaymentId(current.getId())).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(
            value = RefundStatus.class,
            names = {"WAITING", "SKIPPED", "REQUESTED", "FAILED", "DONE"})
    void preservesExistingRefundReasonAndTerminalState(RefundStatus state) {
        Payment payment = failed();
        tx.executeWithoutResult(status -> {
            Payment locked = payments.lockById(payment.getId()).orElseThrow();
            Refund refund = Refund.waiting(locked, RefundReason.USER_CANCEL);
            if (state == RefundStatus.SKIPPED) refund.skip();
            if (state == RefundStatus.REQUESTED || state == RefundStatus.FAILED || state == RefundStatus.DONE) {
                refund.ensureRequested(45_000, now(), now());
            }
            if (state == RefundStatus.FAILED) refund.reject("DECLINED", "declined");
            if (state == RefundStatus.DONE) refund.complete("cancel-id", now());
            refunds.saveAndFlush(refund);
        });
        paymentClock.advance(Duration.ofMinutes(1));
        ReconcileClaim claim = claim();
        reconcile.apply(claim, approved(payment, 45_000));
        reconcile.apply(claim, approved(payment, 45_000));
        Refund refund = refunds.findByPaymentId(payment.getId()).orElseThrow();
        assertThat(refund.getReason()).isEqualTo(RefundReason.USER_CANCEL);
        assertThat(refund.getStatus())
                .isEqualTo(
                        state == RefundStatus.WAITING || state == RefundStatus.SKIPPED
                                ? RefundStatus.REQUESTED
                                : state);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM p_refunds WHERE payment_id=?", Long.class, payment.getId()))
                .isEqualTo(1);
        assertThat(load(payment).getReconcileCount()).isEqualTo(1);
    }

    @Test
    void leaseExpiryReclaimsAfterStopAndOldResultCannotAdvanceNewCheckpoint() {
        Payment payment = failed();
        paymentClock.advance(Duration.ofMinutes(1));
        ReconcileClaim old = claim();
        assertThat(reconcile.claimOne(new ArrayList<>(List.of(0L)))).isEmpty();
        paymentClock.advance(Duration.ofSeconds(30));
        ReconcileClaim current = claim();
        reconcile.apply(old, new PgInquiry.NotFound());
        assertThat(load(payment).getReconcileCount()).isZero();
        reconcile.apply(current, new PgInquiry.NotFound());
        reconcile.apply(old, approved(payment, 45_000));
        assertThat(load(payment).getReconcileCount()).isEqualTo(1);
        assertThat(load(payment).getNextReconcileAt())
                .isEqualTo(payment.getFailedAt().plusMinutes(5));
        assertThat(refunds.findByPaymentId(payment.getId())).isPresent();
    }

    @Test
    void twoClaimersAndConcurrentLateResponseProduceOneRefund() throws Exception {
        Payment payment = failed();
        paymentClock.advance(Duration.ofMinutes(1));
        var pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            var first = pool.submit(() -> {
                start.await();
                return reconcile.claimOne(new ArrayList<>(List.of(0L)));
            });
            var second = pool.submit(() -> {
                start.await();
                return reconcile.claimOne(new ArrayList<>(List.of(0L)));
            });
            start.countDown();
            var a = first.get(5, TimeUnit.SECONDS);
            var b = second.get(5, TimeUnit.SECONDS);
            assertThat((a.isPresent() ? 1 : 0) + (b.isPresent() ? 1 : 0)).isEqualTo(1);
            ReconcileClaim claim = a.orElseGet(b::orElseThrow);
            var apply = pool.submit(() -> reconcile.apply(claim, approved(payment, 45_000)));
            var late = pool.submit(() -> transactions.complete(
                    payment.getId(), new PgResult.Approved(payment.getPgTransactionId(), 45_000, now())));
            apply.get(5, TimeUnit.SECONDS);
            late.get(5, TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM p_refunds WHERE payment_id=?", Long.class, payment.getId()))
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void lockedOldestCandidateIsSkippedWithoutBlockingNext() throws Exception {
        Payment first = failed();
        Payment second = failed();
        paymentClock.advance(Duration.ofMinutes(1));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var holding = pool.submit(() -> tx.executeWithoutResult(status -> {
                payments.lockById(first.getId()).orElseThrow();
                locked.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test lock timeout");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("test interrupted");
                }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(claim().paymentId()).isEqualTo(second.getId());
            release.countDown();
            holding.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void savingRefundAndCheckpointIsAtomicAndFailedCandidateDoesNotStarveOthers() {
        Payment first = failed();
        Payment second = failed();
        paymentClock.advance(Duration.ofMinutes(1));
        pg.inquireReturns(request -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            Payment payment = payments.findByOrderId(request.orderId()).orElseThrow();
            assertThat(payment.getNextReconcileAt()).isAfter(now());
            if (payment.getId().equals(first.getId())) {
                jdbc.execute("ALTER TABLE p_refunds ADD CONSTRAINT ck_reconcile_test CHECK (payment_id <> "
                        + first.getId() + ")");
            } else {
                jdbc.execute("ALTER TABLE p_refunds DROP CONSTRAINT IF EXISTS ck_reconcile_test");
            }
            return approved(payment, 45_000);
        });
        double confirmedBefore = meters.counter("payment.reconcile.confirmed").count();
        try {
            job.runOnce();
        } finally {
            jdbc.execute("ALTER TABLE p_refunds DROP CONSTRAINT IF EXISTS ck_reconcile_test");
        }
        assertThat(load(first).getReconcileCount()).isZero();
        assertThat(refunds.findByPaymentId(first.getId())).isEmpty();
        assertThat(load(second).getReconcileCount()).isEqualTo(1);
        assertThat(meters.counter("payment.reconcile.confirmed").count()).isEqualTo(confirmedBefore + 1);
        paymentClock.advance(Duration.ofSeconds(30));
        pg.inquireReturns(request -> approved(first, 45_000));
        job.runOnce();
        assertThat(refunds.findByPaymentId(first.getId())).isPresent();
    }

    @Test
    void actualHttpLostApprovalResponseTimesOutThenReconcileFindsCharge() {
        String base = com.sparta.ticket.payment.support.pg.FakePgContainer.baseUrl();
        var admin = new com.sparta.ticket.payment.support.pg.FakePgAdmin(base);
        admin.clearFaults();
        Payment payment = ready(IDS.incrementAndGet());
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
        try {
            admin.fault("TOSS", "CONFIRM", "{\"responseLossRate\":1,\"holdMs\":3000}");
            assertThat(service.confirm(payment.getOrderId(), new ConfirmCommand(token, 45_000))
                            .status())
                    .isEqualTo(PaymentStatus.IN_PROGRESS);
            // 실제 PG는 DONE이지만 조회도 불명인 상황에서 Recovery가 시한 종료한다.
            assertThat(admin.transactionStatus(payment.getOrderId())).isEqualTo("DONE");
            paymentClock.advance(Duration.ofSeconds(60));
            pg.inquireReturns(request -> new PgInquiry.Unknown("PG_INQUIRY_UNAVAILABLE"));
            applicationRecovery.runOnce();
            assertThat(load(payment).getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(load(payment).getFailureCode()).isEqualTo("PG_UNRESOLVED");
            admin.clearFaults();
            paymentClock.advance(Duration.ofMinutes(1));
            pg.inquireReturns(http::inquire);
            job.runOnce();
            assertThat(load(payment).getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(load(payment).getReconcileCount()).isEqualTo(1);
            assertThat(refunds.findByPaymentId(payment.getId()).orElseThrow().getStatus())
                    .isEqualTo(RefundStatus.REQUESTED);
            assertThat(pg.approveCalls()).isEqualTo(1);
            assertThat(admin.transactionStatus(payment.getOrderId())).isEqualTo("DONE");
            pg.cancelReturns(http::cancel);
            refundRecovery.runOnce();
            assertThat(refunds.findByPaymentId(payment.getId()).orElseThrow().getStatus())
                    .isEqualTo(RefundStatus.DONE);
            assertThat(admin.transactionStatus(payment.getOrderId())).isEqualTo("CANCELED");
            assertThat(load(payment).getStatus()).isEqualTo(PaymentStatus.FAILED);
        } finally {
            admin.clearFaults();
        }
    }

    @Test
    void budgetLeavesUnstartedWorkUnclaimedAndReportsWaitingAge() {
        Payment first = failed();
        Payment second = failed();
        paymentClock.advance(Duration.ofMinutes(1));
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var bounded = new PaymentReconcileJob(
                reconcile,
                clients,
                new PaymentReconcileProperties(List.of(1L, 5L, 15L, 60L, 1440L), 30, 30, 300, 0.2, 5, 15, 50, 1),
                registry);
        pg.inquireReturns(request -> {
            paymentClock.advance(Duration.ofSeconds(5));
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1100));
            return new PgInquiry.NotFound();
        });
        bounded.runOnce();
        assertThat(load(first).getReconcileCount()).isEqualTo(1);
        assertThat(load(second).getReconcileCount()).isZero();
        assertThat(load(second).getNextReconcileAt()).isEqualTo(second.getNextReconcileAt());
        assertThat(pg.inquiryCalls()).isEqualTo(1);
        assertThat(registry.get("payment.reconcile.oldest.due.age.seconds")
                        .gauge()
                        .value())
                .isGreaterThan(0);
    }

    @Autowired
    private PaymentRecoveryJob applicationRecovery;

    @Autowired
    private com.sparta.ticket.payment.application.service.pg.PgClientRegistry clients;
}
