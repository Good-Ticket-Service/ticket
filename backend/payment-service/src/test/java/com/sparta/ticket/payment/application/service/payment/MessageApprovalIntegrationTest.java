package com.sparta.ticket.payment.application.service.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.payment.application.dto.payment.CheckoutCommand;
import com.sparta.ticket.payment.application.dto.payment.ConfirmCommand;
import com.sparta.ticket.payment.application.dto.payment.MessageConfirmCommand;
import com.sparta.ticket.payment.domain.model.payment.Payment;
import com.sparta.ticket.payment.domain.model.payment.PaymentStatus;
import com.sparta.ticket.payment.domain.model.pg.PgInquiry;
import com.sparta.ticket.payment.domain.model.pg.PgProvider;
import com.sparta.ticket.payment.domain.model.pg.PgResult;
import com.sparta.ticket.payment.domain.repository.payment.PaymentRepository;
import com.sparta.ticket.payment.support.PaymentIntegrationTest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class MessageApprovalIntegrationTest extends PaymentIntegrationTest {
    private static final AtomicLong IDS = new AtomicLong(34_600_000);

    @Autowired
    PaymentService checkout;

    @Autowired
    MessageApprovalService service;

    @Autowired
    MessageApprovalTransactions transactions;

    @Autowired
    PaymentRepository payments;

    @Autowired
    PaymentRecoveryTransactions recovery;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    com.sparta.ticket.payment.application.service.pg.PgClientRegistry clients;

    @Autowired
    PaymentTransactions results;

    private Payment ready(PgProvider provider) {
        String order = "message-" + UUID.randomUUID();
        checkout.checkout(new CheckoutCommand(
                order,
                IDS.incrementAndGet(),
                1L,
                2L,
                10L,
                20L,
                provider,
                10000,
                1000,
                LocalDateTime.now(paymentClock).plusMinutes(1)));
        return payments.findByOrderId(order).orElseThrow();
    }

    private MessageConfirmCommand command(Payment payment) {
        return new MessageConfirmCommand(
                payment.getOrderId(),
                payment.getReservationId(),
                payment.getAmount(),
                payment.getExpiresAt().plusMinutes(3),
                "token-" + payment.getOrderId());
    }

    private Payment load(Payment payment) {
        return payments.findById(payment.getId()).orElseThrow();
    }

    private long results(Payment payment) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM p_payment_result_outbox WHERE payment_id=?", Long.class, payment.getId());
    }

    @ParameterizedTest
    @EnumSource(PgProvider.class)
    void afterHoldExpiryWithinMessageWindowApprovesOnceOutsideTransaction(PgProvider provider) {
        var payment = ready(provider);
        var command = command(payment);
        paymentClock.advance(Duration.ofSeconds(90));
        pg.approveReturns(request -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            return new PgResult.Approved(request.transactionId(), request.amount(), LocalDateTime.now(paymentClock));
        });
        assertThat(service.advance(command, () -> true)).isEqualTo(MessageApprovalService.Outcome.COMPLETE);
        assertThat(service.advance(command, () -> true)).isEqualTo(MessageApprovalService.Outcome.COMPLETE);
        assertThat(load(payment).getStatus()).isEqualTo(PaymentStatus.DONE);
        assertThat(pg.approveCalls()).isEqualTo(1);
        assertThat(results(payment)).isEqualTo(1);
        assertThat(load(payment).getMessageConfirmBefore()).isEqualTo(command.confirmBefore());
    }

    @Test
    void cachedWaitingRegistrationExpiresImmediatelyAtBusinessDeadline() {
        var payment = ready(PgProvider.TOSS);
        var command = command(payment);
        var nanos = new AtomicLong();
        var attempt = new MessageApprovalAttempt(command, nanos::get);
        pg.approvalAllowed(false);
        for (int i = 0; i < 5; i++) {
            nanos.set(i * 100_000_000L);
            assertThat(service.advance(attempt, () -> true)).isEqualTo(MessageApprovalService.Outcome.RETRY);
        }
        assertThat(load(payment).getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(load(payment).getApproveRequestedAt()).isNull();
        paymentClock.advance(Duration.ofMinutes(4));
        assertThat(service.advance(attempt, () -> true)).isEqualTo(MessageApprovalService.Outcome.COMPLETE);
        assertThat(load(payment).getFailureCode()).isEqualTo("EXPIRED");
        assertThat(pg.approveCalls()).isZero();
        assertThat(results(payment)).isEqualTo(1);
    }

    @Test
    void externalCompletionIsObservedAfterBoundedRecheckAndNeverReapproved() {
        var payment = ready(PgProvider.TOSS);
        var command = command(payment);
        var nanos = new AtomicLong();
        var attempt = new MessageApprovalAttempt(command, nanos::get);
        pg.approvalAllowed(false);
        assertThat(service.advance(attempt, () -> true)).isEqualTo(MessageApprovalService.Outcome.RETRY);
        pg.approvalAllowed(true);
        assertThat(service.advance(command, () -> true)).isEqualTo(MessageApprovalService.Outcome.COMPLETE);
        pg.approvalAllowed(false);
        nanos.set(999_999_999L);
        assertThat(service.advance(attempt, () -> true)).isEqualTo(MessageApprovalService.Outcome.RETRY);
        nanos.incrementAndGet();
        assertThat(service.advance(attempt, () -> true)).isEqualTo(MessageApprovalService.Outcome.COMPLETE);
        assertThat(pg.approveCalls()).isEqualTo(1);
        assertThat(results(payment)).isEqualTo(1);
        settlementJobs.drain();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM p_ledger_transactions WHERE source_type='SALE' AND source_id=?",
                        Long.class,
                        payment.getId()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT sum(e.debit-e.credit) FROM p_ledger_entries e JOIN p_ledger_transactions t ON t.id=e.transaction_id WHERE t.source_type='SALE' AND t.source_id=?",
                        Long.class,
                        payment.getId()))
                .isZero();
    }

    @Test
    void rolledBackRegistrationIsNotRememberedByWaitingAttempt() {
        var payment = ready(PgProvider.TOSS);
        var attempt = new MessageApprovalAttempt(command(payment));
        jdbc.execute(
                "ALTER TABLE p_payments ADD CONSTRAINT ck_test_registration_failure CHECK(message_confirm_before IS NULL) NOT VALID");
        try {
            assertThatThrownBy(() -> service.advance(attempt, () -> true)).isInstanceOf(RuntimeException.class);
            assertThat(pg.approveCalls()).isZero();
            assertThat(load(payment).getMessageConfirmBefore()).isNull();
        } finally {
            jdbc.execute("ALTER TABLE p_payments DROP CONSTRAINT ck_test_registration_failure");
        }
        assertThat(service.advance(attempt, () -> true)).isEqualTo(MessageApprovalService.Outcome.COMPLETE);
        assertThat(load(payment).getStatus()).isEqualTo(PaymentStatus.DONE);
        assertThat(pg.approveCalls()).isEqualTo(1);
        assertThat(results(payment)).isEqualTo(1);
    }

    @Test
    void oneProviderCannotOccupyTheOtherProvidersApprovalBudget() throws Exception {
        var settings = new MessageApprovalProperties();
        settings.setWorkers(2);
        settings.setPerProvider(1);
        var isolated = new MessageApprovalService(transactions, results, clients, paymentClock, settings);
        var first = ready(PgProvider.TOSS);
        var waiting = ready(PgProvider.TOSS);
        var other = ready(PgProvider.KAKAO);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        pg.approveReturns(request -> {
            if (request.orderId().equals(first.getOrderId())) {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted");
                }
            }
            return new PgResult.Approved(request.transactionId(), request.amount(), LocalDateTime.now(paymentClock));
        });
        var pool = Executors.newSingleThreadExecutor();
        try {
            var active = pool.submit(() -> isolated.advance(command(first), () -> true));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(isolated.advance(command(waiting), () -> true)).isEqualTo(MessageApprovalService.Outcome.RETRY);
            assertThat(isolated.advance(command(other), () -> true)).isEqualTo(MessageApprovalService.Outcome.COMPLETE);
            assertThat(load(other).getStatus()).isEqualTo(PaymentStatus.DONE);
            assertThat(load(waiting).getApproveRequestedAt()).isNull();
            release.countDown();
            assertThat(active.get(5, TimeUnit.SECONDS)).isEqualTo(MessageApprovalService.Outcome.COMPLETE);
            assertThat(pg.approveCalls()).isEqualTo(2);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void sameLocalRestrictionIsFinalInHttpButWaitsAndCompletesInMessageMode() {
        var synchronous = ready(PgProvider.TOSS);
        var asynchronous = ready(PgProvider.TOSS);
        var command = command(asynchronous);
        pg.approvalAllowed(false);
        assertThat(checkout.confirm(
                                synchronous.getOrderId(),
                                new ConfirmCommand("http-local-limit", synchronous.getAmount()))
                        .status())
                .isEqualTo(PaymentStatus.FAILED);
        assertThat(service.advance(command, () -> true)).isEqualTo(MessageApprovalService.Outcome.RETRY);
        assertThat(load(asynchronous).getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(results(synchronous)).isEqualTo(1);
        assertThat(results(asynchronous)).isZero();
        assertThat(pg.approveCalls()).isZero();
        pg.approvalAllowed(true);
        assertThat(service.advance(command, () -> true)).isEqualTo(MessageApprovalService.Outcome.COMPLETE);
        assertThat(load(asynchronous).getStatus()).isEqualTo(PaymentStatus.DONE);
        assertThat(pg.approveCalls()).isEqualTo(1);
        assertThat(results(asynchronous)).isEqualTo(1);
    }

    @Test
    void localPermitWaitDoesNotBecomeFailedOrStartSixtySecondClock() {
        var payment = ready(PgProvider.TOSS);
        var command = command(payment);
        pg.approvalAllowed(false);
        assertThat(service.advance(command, () -> true)).isEqualTo(MessageApprovalService.Outcome.RETRY);
        assertThat(load(payment).getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(load(payment).getApproveRequestedAt()).isNull();
        assertThat(load(payment).getNextAttemptAt()).isNull();
        pg.approvalAllowed(true);
        assertThat(service.advance(command, () -> true)).isEqualTo(MessageApprovalService.Outcome.COMPLETE);
        assertThat(pg.approveCalls()).isEqualTo(1);
        assertThat(load(payment).getStatus()).isEqualTo(PaymentStatus.DONE);
    }

    @Test
    void localPermitWaitEndsAtFixedDeadlineWithoutCallingPg() {
        var payment = ready(PgProvider.TOSS);
        var command = command(payment);
        pg.approvalAllowed(false);
        assertThat(service.advance(command, () -> true)).isEqualTo(MessageApprovalService.Outcome.RETRY);
        paymentClock.advance(Duration.ofMinutes(4));
        assertThat(service.advance(command, () -> true)).isEqualTo(MessageApprovalService.Outcome.COMPLETE);
        assertThat(load(payment).getFailureCode()).isEqualTo("EXPIRED");
        assertThat(load(payment).getApproveRequestedAt()).isNull();
        assertThat(pg.approveCalls()).isZero();
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void claimTransactionFailureCannotReachPg(org.springframework.boot.test.system.CapturedOutput output) {
        var payment = ready(PgProvider.TOSS);
        var command = command(payment);
        transactions.register(command);
        jdbc.execute(
                "ALTER TABLE p_payments ADD CONSTRAINT ck_test_no_message_start CHECK(message_confirm_before IS NULL OR status<>'IN_PROGRESS') NOT VALID");
        try {
            assertThatThrownBy(() -> service.advance(command, () -> true)).isInstanceOf(RuntimeException.class);
            assertThat(pg.approveCalls()).isZero();
            assertThat(load(payment).getStatus()).isEqualTo(PaymentStatus.READY);
            assertThat(load(payment).getApproveRequestedAt()).isNull();
        } finally {
            jdbc.execute("ALTER TABLE p_payments DROP CONSTRAINT ck_test_no_message_start");
        }
        assertThat(output.getAll()).doesNotContain(command.pgAuthToken());
    }

    @Test
    void unknownDeadlineKeepsFirstFailureAndLateApprovalCreatesOnlyCompensation() {
        var payment = ready(PgProvider.TOSS);
        var command = command(payment);
        pg.approveReturns(request -> new PgResult.Unknown("synthetic response loss"));
        assertThat(service.advance(command, () -> true)).isEqualTo(MessageApprovalService.Outcome.COMPLETE);
        paymentClock.advance(Duration.ofSeconds(61));
        var claim = new com.sparta.ticket.payment.application.dto.payment.RecoveryClaim(
                payment.getId(),
                payment.getOrderId(),
                payment.getPgProvider(),
                command.pgAuthToken(),
                load(payment).getNextAttemptAt());
        recovery.apply(claim, new PgInquiry.Unknown("synthetic unknown"));
        assertThat(load(payment).getFailureCode()).isEqualTo("PG_UNRESOLVED");
        String original = jdbc.queryForObject(
                "SELECT payload FROM p_payment_result_outbox WHERE payment_id=?", String.class, payment.getId());
        recovery.apply(
                claim,
                new PgInquiry.Approved(command.pgAuthToken(), payment.getAmount(), LocalDateTime.now(paymentClock)));
        assertThat(load(payment).getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(jdbc.queryForObject(
                        "SELECT reason FROM p_refunds WHERE payment_id=?", String.class, payment.getId()))
                .isEqualTo("AUTO_VOID");
        assertThat(jdbc.queryForObject(
                        "SELECT payload FROM p_payment_result_outbox WHERE payment_id=?",
                        String.class,
                        payment.getId()))
                .isEqualTo(original);
        assertThat(pg.approveCalls()).isEqualTo(1);
    }

    @Test
    void registrationKeepsReadyAndHttpCannotStealMessageExecution() {
        var payment = ready(PgProvider.TOSS);
        var command = command(payment);
        transactions.register(command);
        assertThat(load(payment).getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(load(payment).getApproveRequestedAt()).isNull();
        assertThat(checkout.confirm(
                                payment.getOrderId(), new ConfirmCommand(command.pgAuthToken(), payment.getAmount()))
                        .status())
                .isEqualTo(PaymentStatus.IN_PROGRESS);
        assertThat(pg.approveCalls()).isZero();
        assertThat(load(payment).getStatus()).isEqualTo(PaymentStatus.READY);
        assertThatThrownBy(() -> transactions.register(new MessageConfirmCommand(
                        command.orderId(),
                        command.reservationId(),
                        command.amount(),
                        command.confirmBefore().plusSeconds(1),
                        command.pgAuthToken())))
                .isInstanceOf(BusinessException.class);
        assertThat(load(payment).getMessageConfirmBefore()).isEqualTo(command.confirmBefore());
    }

    @Test
    void expiryAndResultOutboxAreAtomicAtExactDeadline() {
        var payment = ready(PgProvider.TOSS);
        var command = command(payment);
        paymentClock.advance(Duration.ofMinutes(4));
        publisher.fail = true;
        assertThatThrownBy(() -> transactions.register(command)).isInstanceOf(IllegalStateException.class);
        assertThat(load(payment).getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(load(payment).getMessageConfirmBefore()).isNull();
        publisher.fail = false;
        assertThat(service.advance(command, () -> true)).isEqualTo(MessageApprovalService.Outcome.COMPLETE);
        assertThat(load(payment).getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(load(payment).getFailureCode()).isEqualTo("EXPIRED");
        assertThat(load(payment).getApproveRequestedAt()).isNull();
        assertThat(pg.approveCalls()).isZero();
        assertThat(results(payment)).isEqualTo(1);
    }

    @Test
    void resultRollbackTransfersToDurableInquiryWithoutReapproval() {
        var payment = ready(PgProvider.TOSS);
        var command = command(payment);
        var attempt = new MessageApprovalAttempt(command);
        pg.approvalAllowed(false);
        assertThat(service.advance(attempt, () -> true)).isEqualTo(MessageApprovalService.Outcome.RETRY);
        pg.approvalAllowed(true);
        publisher.fail = true;
        assertThat(service.advance(attempt, () -> true)).isEqualTo(MessageApprovalService.Outcome.COMPLETE);
        assertThat(load(payment).getStatus()).isEqualTo(PaymentStatus.IN_PROGRESS);
        assertThat(load(payment).getNextAttemptAt()).isNotNull();
        assertThat(results(payment)).isZero();
        assertThat(service.advance(attempt, () -> true)).isEqualTo(MessageApprovalService.Outcome.COMPLETE);
        assertThat(pg.approveCalls()).isEqualTo(1);
        publisher.fail = false;
        paymentClock.advance(Duration.ofSeconds(11));
        // Note: 이 거래만 점유해 공유 DB에 남은 다른 시험의 복구 후보와 분리한다.
        tx.executeWithoutResult(status -> payments.lockById(payment.getId())
                .orElseThrow()
                .claimRecovery(LocalDateTime.now(paymentClock).plusSeconds(10)));
        var claim = new com.sparta.ticket.payment.application.dto.payment.RecoveryClaim(
                payment.getId(),
                payment.getOrderId(),
                payment.getPgProvider(),
                command.pgAuthToken(),
                load(payment).getNextAttemptAt());
        recovery.apply(
                claim,
                new PgInquiry.Approved(command.pgAuthToken(), payment.getAmount(), LocalDateTime.now(paymentClock)));
        assertThat(load(payment).getStatus()).isEqualTo(PaymentStatus.DONE);
        assertThat(results(payment)).isEqualTo(1);
        assertThat(pg.approveCalls()).isEqualTo(1);
    }

    @Test
    void duplicateClaimsAndLockWaitPastDeadlineNeverCallPgAgain() throws Exception {
        var payment = ready(PgProvider.TOSS);
        var command = command(payment);
        transactions.register(command);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var holder = pool.submit(() -> tx.executeWithoutResult(status -> {
                payments.lockById(payment.getId()).orElseThrow();
                locked.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted");
                }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var claim = pool.submit(() -> transactions.claim(command));
            paymentClock.advance(Duration.ofMinutes(4));
            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
            assertThat(claim.get(5, TimeUnit.SECONDS)).isEmpty();
            assertThat(load(payment).getFailureCode()).isEqualTo("EXPIRED");
            assertThat(pg.approveCalls()).isZero();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void claimCommittedBeforeProcessStopsIsNeverReapprovedOnRedelivery() {
        var payment = ready(PgProvider.TOSS);
        var command = command(payment);
        transactions.register(command);
        assertThat(transactions.claim(command)).isPresent();
        assertThat(transactions.claim(command)).isEmpty();
        assertThat(service.advance(command, () -> true)).isEqualTo(MessageApprovalService.Outcome.COMPLETE);
        assertThat(pg.approveCalls()).isZero();
        assertThat(load(payment).getStatus()).isEqualTo(PaymentStatus.IN_PROGRESS);
        assertThat(load(payment).getNextAttemptAt()).isNotNull();
    }

    @Test
    void readyCleanupUsesStoredDeadlineEvenIfConsumerIsOff() {
        var payment = ready(PgProvider.TOSS);
        var command = command(payment);
        transactions.register(command);
        paymentClock.advance(Duration.ofMinutes(2));
        var excluded = new java.util.ArrayList<Long>(java.util.List.of(0L));
        for (int count = 0; count < 100 && recovery.expireOne(excluded); count++) {}
        assertThat(load(payment).getStatus()).isEqualTo(PaymentStatus.READY);
        paymentClock.advance(Duration.ofMinutes(2));
        excluded = new java.util.ArrayList<>(java.util.List.of(0L));
        for (int count = 0; count < 100 && recovery.expireOne(excluded); count++) {}
        assertThat(load(payment).getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void acceptedIsCalledOnceRightAfterClaimCommitAndPgIsCalledEvenIfOwnershipIsLost() {
        var payment = ready(PgProvider.TOSS);
        var command = command(payment);
        var owner = new java.util.concurrent.atomic.AtomicBoolean(true);
        var statusAtAccept = new java.util.concurrent.atomic.AtomicReference<PaymentStatus>();
        var accepted = new java.util.concurrent.atomic.AtomicInteger();
        pg.approveReturns(request -> {
            assertThat(accepted.get()).isEqualTo(1);
            return new PgResult.Approved(request.transactionId(), request.amount(), LocalDateTime.now(paymentClock));
        });

        var outcome = service.advance(new MessageApprovalAttempt(command), owner::get, () -> {
            accepted.incrementAndGet();
            // Note: claim TX가 커밋된 뒤라 다른 TX에서 IN_PROGRESS가 보여야 한다.
            statusAtAccept.set(tx.execute(status -> load(payment).getStatus()));
            owner.set(false);
        });

        assertThat(outcome).isEqualTo(MessageApprovalService.Outcome.COMPLETE);
        assertThat(accepted.get()).isEqualTo(1);
        assertThat(statusAtAccept.get()).isEqualTo(PaymentStatus.IN_PROGRESS);
        assertThat(pg.approveCalls()).isEqualTo(1);
        assertThat(load(payment).getStatus()).isEqualTo(PaymentStatus.DONE);
        assertThat(results(payment)).isEqualTo(1);
    }

    @Test
    void acceptedIsNotCalledWhenNothingIsClaimed() {
        var waiting = ready(PgProvider.TOSS);
        var accepted = new java.util.concurrent.atomic.AtomicInteger();
        pg.approvalAllowed(false);
        assertThat(service.advance(new MessageApprovalAttempt(command(waiting)), () -> true, accepted::incrementAndGet))
                .isEqualTo(MessageApprovalService.Outcome.RETRY);
        pg.approvalAllowed(true);

        var done = ready(PgProvider.TOSS);
        assertThat(service.advance(command(done), () -> true)).isEqualTo(MessageApprovalService.Outcome.COMPLETE);
        assertThat(service.advance(new MessageApprovalAttempt(command(done)), () -> true, accepted::incrementAndGet))
                .isEqualTo(MessageApprovalService.Outcome.COMPLETE);

        var expired = ready(PgProvider.TOSS);
        var expiredCommand = command(expired);
        paymentClock.advance(Duration.ofMinutes(5));
        assertThat(service.advance(new MessageApprovalAttempt(expiredCommand), () -> true, accepted::incrementAndGet))
                .isEqualTo(MessageApprovalService.Outcome.COMPLETE);

        assertThat(accepted.get()).isZero();
    }
}
