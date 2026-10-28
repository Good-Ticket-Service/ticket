package com.sparta.ticket.payment.application.service.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.payment.application.dto.payment.CheckoutCommand;
import com.sparta.ticket.payment.application.dto.payment.ConfirmCommand;
import com.sparta.ticket.payment.application.dto.payment.ConfirmResult;
import com.sparta.ticket.payment.domain.model.payment.Payment;
import com.sparta.ticket.payment.domain.model.payment.PaymentFailureCode;
import com.sparta.ticket.payment.domain.model.payment.PaymentStatus;
import com.sparta.ticket.payment.domain.model.pg.PgProvider;
import com.sparta.ticket.payment.domain.model.pg.PgResult;
import com.sparta.ticket.payment.domain.model.refund.Refund;
import com.sparta.ticket.payment.domain.model.refund.RefundReason;
import com.sparta.ticket.payment.domain.model.refund.RefundStatus;
import com.sparta.ticket.payment.domain.repository.payment.PaymentRepository;
import com.sparta.ticket.payment.domain.repository.refund.RefundRepository;
import com.sparta.ticket.payment.support.PaymentIntegrationTest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class PaymentConfirmIntegrationTest extends PaymentIntegrationTest {
    private static final AtomicLong IDS = new AtomicLong(4_000_000);

    @Autowired
    private PaymentService service;

    @Autowired
    private PaymentTransactions transactions;

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private RefundRepository refunds;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private Clock clock;

    private Payment ready(PgProvider provider) {
        String order = "confirm-" + UUID.randomUUID();
        service.checkout(new CheckoutCommand(
                order,
                IDS.incrementAndGet(),
                1L,
                2L,
                10L,
                20L,
                provider,
                50_000,
                5_000,
                LocalDateTime.now(clock).plusMinutes(10)));
        return load(order);
    }

    private Payment load(String order) {
        return payments.findByOrderId(order).orElseThrow();
    }

    private void change(Payment payment, Consumer<Payment> action) {
        tx.executeWithoutResult(
                status -> action.accept(payments.lockById(payment.getId()).orElseThrow()));
    }

    private ConfirmResult confirm(Payment payment, String token) {
        return service.confirm(payment.getOrderId(), new ConfirmCommand(token, payment.getAmount()));
    }

    private void waiting(Payment payment, RefundReason reason) {
        tx.executeWithoutResult(status -> {
            Payment locked = payments.lockById(payment.getId()).orElseThrow();
            refunds.saveAndFlush(Refund.waiting(locked, reason));
        });
    }

    @ParameterizedTest
    @EnumSource(PgProvider.class)
    void approvedOutsideTransactionAndReplayed(PgProvider provider) {
        Payment payment = ready(provider);
        pg.approveReturns(request -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            Payment stored = load(payment.getOrderId());
            assertThat(stored.getStatus()).isEqualTo(PaymentStatus.IN_PROGRESS);
            assertThat(stored.getConfirmRequestedAt()).isNotNull();
            assertThat(stored.getApproveRequestedAt()).isNotNull();
            assertThat(request.amount()).isEqualTo(payment.getAmount());
            assertThat(request.transactionId())
                    .isEqualTo(provider == PgProvider.TOSS ? "token" : payment.getPgTransactionId());
            return new PgResult.Approved(request.transactionId(), request.amount(), LocalDateTime.now(clock));
        });
        assertThat(confirm(payment, "token").status()).isEqualTo(PaymentStatus.DONE);
        assertThat(service.confirm(payment.getOrderId(), new ConfirmCommand("different", 1))
                        .status())
                .isEqualTo(PaymentStatus.DONE);
        assertThat(pg.approveCalls()).isEqualTo(1);
    }

    @Test
    void wrongAmountDoesNotRecordReceiptOrCallPg() {
        Payment payment = ready(PgProvider.TOSS);
        assertThatThrownBy(() -> service.confirm(payment.getOrderId(), new ConfirmCommand("token", 1)))
                .isInstanceOf(BusinessException.class);
        Payment stored = load(payment.getOrderId());
        assertThat(stored.getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(stored.getConfirmRequestedAt()).isNull();
        assertThat(pg.approveCalls()).isZero();
    }

    @Test
    void expiredAndOpenCircuitNeverCallPg() {
        Payment expired = ready(PgProvider.TOSS);
        // 선점 만료는 실제 시각을 기다리지 않고 SQL로 경계를 준비한다.
        org.springframework.test.util.ReflectionTestUtils.setField(
                expired, "expiresAt", LocalDateTime.now(clock).minusSeconds(1));
        tx.executeWithoutResult(status -> payments.saveAndFlush(expired));
        assertThat(confirm(expired, "expired").failureCode()).isEqualTo("EXPIRED");
        Payment unavailable = ready(PgProvider.TOSS);
        pg.approvalAllowed(false);
        assertThat(confirm(unavailable, "unavailable").failureCode()).isEqualTo("PG_UNAVAILABLE");
        assertThat(pg.approveCalls()).isZero();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void reusedTossKeyOnlyFailsSecondPayment(CapturedOutput output) {
        Payment first = ready(PgProvider.TOSS);
        Payment second = ready(PgProvider.TOSS);
        assertThat(confirm(first, "shared-key").status()).isEqualTo(PaymentStatus.DONE);
        assertThat(confirm(second, "shared-key").failureCode()).isEqualTo("INVALID_AUTH_TOKEN");
        assertThat(load(first.getOrderId()).getStatus()).isEqualTo(PaymentStatus.DONE);
        assertThat(load(second.getOrderId()).getPgTransactionId()).isNull();
        assertThat(pg.approveCalls()).isEqualTo(1);
        assertThat(output.getAll()).doesNotContain("shared-key");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void simultaneousReuseOfKeyHasOneApprovalWithoutLeakingKey(CapturedOutput output) throws Exception {
        Payment first = ready(PgProvider.TOSS);
        Payment second = ready(PgProvider.TOSS);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ConfirmResult> a = pool.submit(() -> {
                start.await();
                return confirm(first, "concurrent-shared-secret");
            });
            Future<ConfirmResult> b = pool.submit(() -> {
                start.await();
                return confirm(second, "concurrent-shared-secret");
            });
            start.countDown();
            assertThat(List.of(
                            a.get(10, TimeUnit.SECONDS).status(),
                            b.get(10, TimeUnit.SECONDS).status()))
                    .containsExactlyInAnyOrder(PaymentStatus.DONE, PaymentStatus.FAILED);
            assertThat(pg.approveCalls()).isEqualTo(1);
            assertThat(output.getAll()).doesNotContain("concurrent-shared-secret");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void fiftyRequestsOnlyCallPgOnceEvenWhenCircuitOpens() throws Exception {
        Payment payment = ready(PgProvider.TOSS);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        pg.approveReturns(request -> {
            entered.countDown();
            try {
                if (!release.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("승인 대기 초과");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return new PgResult.Approved(request.transactionId(), request.amount(), LocalDateTime.now(clock));
        });
        ExecutorService pool = Executors.newFixedThreadPool(50);
        List<Future<ConfirmResult>> calls = new ArrayList<>();
        try {
            calls.add(pool.submit(() -> confirm(payment, "concurrent-key")));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            pg.approvalAllowed(false);
            for (int i = 1; i < 50; i++) calls.add(pool.submit(() -> confirm(payment, "concurrent-key")));
            for (int i = 1; i < 50; i++) {
                assertThat(calls.get(i).get(15, TimeUnit.SECONDS).status()).isEqualTo(PaymentStatus.IN_PROGRESS);
            }
            release.countDown();
            assertThat(calls.get(0).get(10, TimeUnit.SECONDS).status()).isEqualTo(PaymentStatus.DONE);
            assertThat(pg.approveCalls()).isEqualTo(1);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void unknownAndThrownExceptionKeepRecoverableStateWithoutSecrets() {
        Payment payment = ready(PgProvider.TOSS);
        pg.approveReturns(request -> {
            throw new IllegalStateException("SECRET_TOKEN");
        });
        assertThat(confirm(payment, "SECRET_TOKEN").status()).isEqualTo(PaymentStatus.IN_PROGRESS);
        Payment stored = load(payment.getOrderId());
        assertThat(stored.getNextAttemptAt()).isNotNull();
        assertThat(stored.getRecoveryStartedAt()).isNotNull();
        assertThat(stored.getRecoveryAttempts()).isEqualTo(1);
        assertThat(stored.getLastError()).doesNotContain("SECRET_TOKEN");
        assertThat(confirm(payment, "SECRET_TOKEN").status()).isEqualTo(PaymentStatus.IN_PROGRESS);
        assertThat(pg.approveCalls()).isEqualTo(1);
    }

    @Test
    void invalidApprovalDoesNotFinalizeOrRefundAnotherTransaction() {
        Payment payment = ready(PgProvider.TOSS);
        pg.approveReturns(request -> new PgResult.Approved("someone-else", request.amount(), LocalDateTime.now(clock)));
        assertThat(confirm(payment, "expected").status()).isEqualTo(PaymentStatus.IN_PROGRESS);
        assertThat(refunds.findByPaymentId(payment.getId())).isEmpty();
        assertThat(load(payment.getOrderId()).getLastError()).isEqualTo("PG_INVALID_APPROVAL");
    }

    @Test
    void amountMismatchAtomicallyCreatesActualAmountRefund() {
        Payment payment = ready(PgProvider.TOSS);
        pg.approveReturns(request -> new PgResult.Approved(request.transactionId(), 46_000, LocalDateTime.now(clock)));
        assertThat(confirm(payment, "wrong-amount").failureCode()).isEqualTo("AMOUNT_MISMATCH");
        Refund refund = refunds.findByPaymentId(payment.getId()).orElseThrow();
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(refund.getReason()).isEqualTo(RefundReason.AUTO_VOID);
        assertThat(refund.getRefundAmount()).isEqualTo(46_000);
        assertThat(refund.getNextAttemptAt()).isNotNull();
    }

    @Test
    void rejectedSkipsWaitingAndLateApprovalReusesSameRefund() {
        Payment payment = ready(PgProvider.TOSS);
        pg.approveReturns(request -> {
            waiting(payment, RefundReason.USER_CANCEL);
            return new PgResult.Rejected("DECLINED", "secret");
        });
        assertThat(confirm(payment, "late-key").status()).isEqualTo(PaymentStatus.FAILED);
        Refund skipped = refunds.findByPaymentId(payment.getId()).orElseThrow();
        assertThat(skipped.getStatus()).isEqualTo(RefundStatus.SKIPPED);
        PgResult.Approved late = new PgResult.Approved("late-key", 45_000, LocalDateTime.now(clock));
        transactions.complete(payment.getId(), late);
        transactions.complete(payment.getId(), late);
        Refund refund = refunds.findByPaymentId(payment.getId()).orElseThrow();
        assertThat(refund.getId()).isEqualTo(skipped.getId());
        assertThat(refund.getReason()).isEqualTo(RefundReason.USER_CANCEL);
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(load(payment.getOrderId()).getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void approvedPromotesWaitingRefund() {
        Payment payment = ready(PgProvider.KAKAO);
        pg.approveReturns(request -> {
            waiting(payment, RefundReason.SCHEDULE_CANCELED);
            return new PgResult.Approved(request.transactionId(), request.amount(), LocalDateTime.now(clock));
        });
        assertThat(confirm(payment, "pg-token").status()).isEqualTo(PaymentStatus.DONE);
        Refund refund = refunds.findByPaymentId(payment.getId()).orElseThrow();
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(refund.getReason()).isEqualTo(RefundReason.SCHEDULE_CANCELED);
    }

    @Test
    void resultPersistenceFailureRollsBackPaymentAndRefundAndCanBeRecovered() {
        Payment payment = ready(PgProvider.TOSS);
        pg.approveReturns(request -> new PgResult.Approved(request.transactionId(), 46_000, LocalDateTime.now(clock)));
        publisher.fail = true;
        assertThat(confirm(payment, "rollback-key").status()).isEqualTo(PaymentStatus.IN_PROGRESS);
        assertThat(refunds.findByPaymentId(payment.getId())).isEmpty();
        publisher.fail = false;
        transactions.complete(payment.getId(), new PgResult.Approved("rollback-key", 46_000, LocalDateTime.now(clock)));
        assertThat(load(payment.getOrderId()).getFailureCode()).isEqualTo("AMOUNT_MISMATCH");
        assertThat(refunds.findByPaymentId(payment.getId())).isPresent();
        assertThat(pg.approveCalls()).isEqualTo(1);
    }

    @Test
    void receiptAndStartedApprovalSurviveProcessStopWithoutReapproval() {
        Payment payment = ready(PgProvider.TOSS);
        transactions.requestConfirm(payment.getOrderId(), new ConfirmCommand("stop-key", 45_000));
        assertThat(load(payment.getOrderId()).isConfirmRequested()).isTrue();
        assertThat(load(payment.getOrderId()).getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(transactions.markInProgress(payment.getId(), "stop-key")).isPresent();
        assertThat(confirm(payment, "stop-key").status()).isEqualTo(PaymentStatus.IN_PROGRESS);
        assertThat(load(payment.getOrderId()).getNextAttemptAt()).isNotNull();
        assertThat(pg.approveCalls()).isZero();
    }

    @Test
    void terminalResultCannotBeReversedByLateUnknownOrRejected() {
        Payment payment = ready(PgProvider.TOSS);
        confirm(payment, "done-key");
        transactions.complete(payment.getId(), new PgResult.Rejected("DECLINED", "late"));
        transactions.complete(payment.getId(), new PgResult.Unknown("late"));
        assertThat(load(payment.getOrderId()).getStatus()).isEqualTo(PaymentStatus.DONE);
    }

    @Test
    void failedPaymentConcurrentLateResultsHaveOneRefund() throws Exception {
        Payment payment = ready(PgProvider.TOSS);
        pg.approveReturns(request -> new PgResult.Unknown("timeout"));
        confirm(payment, "failed-late");
        change(payment, p -> p.fail(PaymentFailureCode.PG_UNRESOLVED, "timeout"));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> calls = new ArrayList<>();
            for (int i = 0; i < 20; i++)
                calls.add(pool.submit(() -> transactions.complete(
                        payment.getId(), new PgResult.Approved("failed-late", 45_000, LocalDateTime.now(clock)))));
            for (Future<?> call : calls) call.get(15, TimeUnit.SECONDS);
            assertThat(refunds.findByPaymentId(payment.getId()).orElseThrow().getStatus())
                    .isEqualTo(RefundStatus.REQUESTED);
            assertThat(load(payment.getOrderId()).getStatus()).isEqualTo(PaymentStatus.FAILED);
        } finally {
            pool.shutdownNow();
        }
    }

    @ParameterizedTest
    @EnumSource(RefundStatus.class)
    void ensureRefundPreservesExistingReasonAndFinalStates(RefundStatus original) {
        Payment payment = ready(PgProvider.TOSS);
        pg.approveReturns(request -> new PgResult.Unknown("timeout"));
        confirm(payment, "refund-states-" + payment.getId());
        change(payment, p -> p.fail(PaymentFailureCode.PG_UNRESOLVED, "timeout"));
        tx.executeWithoutResult(status -> {
            Payment locked = payments.lockById(payment.getId()).orElseThrow();
            Refund refund = Refund.waiting(locked, RefundReason.USER_CANCEL);
            LocalDateTime now = LocalDateTime.now(clock);
            if (original == RefundStatus.SKIPPED) {
                refund.skip();
            } else if (original != RefundStatus.WAITING) {
                refund.ensureRequested(45_000, now, now);
                if (original == RefundStatus.DONE) refund.complete("cancel-id", now);
                if (original == RefundStatus.FAILED) refund.reject("DECLINED", "거절");
            }
            refunds.saveAndFlush(refund);
        });
        Refund before = refunds.findByPaymentId(payment.getId()).orElseThrow();
        transactions.complete(
                payment.getId(),
                new PgResult.Approved("refund-states-" + payment.getId(), 46_000, LocalDateTime.now(clock)));
        Refund after = refunds.findByPaymentId(payment.getId()).orElseThrow();
        boolean promoted = original == RefundStatus.WAITING || original == RefundStatus.SKIPPED;
        assertThat(after.getStatus()).isEqualTo(promoted ? RefundStatus.REQUESTED : original);
        assertThat(after.getRefundAmount()).isEqualTo(promoted ? 46_000 : 45_000);
        assertThat(after.getId()).isEqualTo(before.getId());
        assertThat(after.getReason()).isEqualTo(RefundReason.USER_CANCEL);
        assertThat(after.getCancelAttempt()).isEqualTo(before.getCancelAttempt());
    }

    @Test
    void lateApprovalOnlyRefundsOldAttemptAndPreservesNewPayment() {
        Payment old = ready(PgProvider.TOSS);
        pg.approveReturns(request -> new PgResult.Unknown("timeout"));
        confirm(old, "old-attempt");
        change(old, p -> p.fail(PaymentFailureCode.PG_UNRESOLVED, "timeout"));
        String nextOrder = "new-attempt-" + UUID.randomUUID();
        service.checkout(new CheckoutCommand(
                nextOrder,
                old.getReservationId(),
                1L,
                2L,
                10L,
                20L,
                PgProvider.TOSS,
                50_000,
                5_000,
                LocalDateTime.now(clock).plusMinutes(10)));
        transactions.complete(old.getId(), new PgResult.Approved("old-attempt", 45_000, LocalDateTime.now(clock)));
        assertThat(load(nextOrder).getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(refunds.findByPaymentId(load(nextOrder).getId())).isEmpty();
        assertThat(refunds.findByPaymentId(old.getId())).isPresent();
    }
}
