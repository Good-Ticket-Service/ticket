package com.sparta.ticket.payment.application.service.refund;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.payment.application.dto.refund.RefundClaim;
import com.sparta.ticket.payment.application.dto.refund.RefundResult;
import com.sparta.ticket.payment.application.port.payment.PaymentResultPublisher;
import com.sparta.ticket.payment.application.service.payment.PaymentTransactions;
import com.sparta.ticket.payment.domain.exception.payment.PaymentErrorCode;
import com.sparta.ticket.payment.domain.model.payment.Payment;
import com.sparta.ticket.payment.domain.model.payment.PaymentFailureCode;
import com.sparta.ticket.payment.domain.model.payment.PaymentStatus;
import com.sparta.ticket.payment.domain.model.pg.PgInquiry;
import com.sparta.ticket.payment.domain.model.pg.PgResult;
import com.sparta.ticket.payment.domain.model.refund.Refund;
import com.sparta.ticket.payment.domain.model.refund.RefundReason;
import com.sparta.ticket.payment.domain.model.refund.RefundRecoveryMode;
import com.sparta.ticket.payment.domain.model.refund.RefundStatus;
import com.sparta.ticket.payment.domain.repository.payment.PaymentRepository;
import com.sparta.ticket.payment.domain.repository.refund.RefundRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@Slf4j
@RequiredArgsConstructor
public class RefundTransactions {
    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final PaymentTransactions paymentTransactions;
    private final PaymentResultPublisher publisher;
    private final RefundRecoveryProperties properties;
    private final Clock clock;
    private final MeterRegistry meters;

    @Transactional
    public RefundResult accept(String orderId, RefundReason reason) {
        if (reason == null || reason == RefundReason.AUTO_VOID) {
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR);
        }
        Payment payment = locked(orderId);
        Optional<Refund> existing = refunds.lockByPaymentId(payment.getId());
        if (existing.isPresent()) return RefundResult.from(existing.get());
        LocalDateTime now = LocalDateTime.now(clock);
        return switch (payment.getStatus()) {
            case READY -> {
                paymentTransactions.failForRecovery(payment, PaymentFailureCode.ABANDONED);
                yield RefundResult.notRequired();
            }
            case FAILED -> RefundResult.notRequired();
            case IN_PROGRESS -> RefundResult.from(refunds.saveAndFlush(Refund.waiting(payment, reason)));
            case DONE ->
                RefundResult.from(
                        refunds.saveAndFlush(Refund.requested(payment, reason, payment.getAmount(), now, now)));
            case CANCELED -> throw new IllegalStateException("취소 결제의 환불 기록이 없다");
        };
    }

    @Transactional(readOnly = true)
    public RefundResult find(String orderId) {
        Payment payment = payments.findByOrderId(orderId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));
        return refunds.findByPaymentId(payment.getId()).map(RefundResult::from).orElseGet(RefundResult::notRequired);
    }

    @Transactional
    public Optional<RefundClaim> claim(String orderId) {
        return claimLocked(locked(orderId));
    }

    @Transactional
    public Optional<RefundClaim> claimOne(Collection<Long> attemptedIds) {
        Optional<Payment> next = payments.lockNextRefund(LocalDateTime.now(clock), attemptedIds).stream()
                .findFirst();
        if (next.isEmpty()) return Optional.empty();
        attemptedIds.add(next.get().getId());
        return claimLocked(next.get());
    }

    @Transactional
    public Optional<RefundClaim> claimOne(Collection<Long> attemptedIds, boolean followUp) {
        var preferred = followUp ? java.util.List.of("SLOW", "VERIFY_ONLY") : java.util.List.of("ACTIVE");
        Optional<Payment> next =
                payments.lockNextRefundInModes(LocalDateTime.now(clock), attemptedIds, preferred).stream()
                        .findFirst();
        if (next.isEmpty()) return Optional.empty();
        attemptedIds.add(next.get().getId());
        return claimLocked(next.get());
    }

    private Optional<RefundClaim> claimLocked(Payment payment) {
        Refund refund = refunds.lockByPaymentId(payment.getId()).orElse(null);
        LocalDateTime now = LocalDateTime.now(clock);
        if (refund == null
                || !refund.isAutomaticallyQueryable()
                || refund.getNextAttemptAt() == null
                || refund.getNextAttemptAt().isAfter(now)) return Optional.empty();
        refreshPolicy(payment, refund, now);
        boolean inquireFirst =
                refund.getExecutionVersion() > 0 || refund.getRecoveryMode() != RefundRecoveryMode.ACTIVE;
        refund.claimRecovery(nextAttempt(refund, now));
        return Optional.of(new RefundClaim(
                payment.getId(),
                payment.getOrderId(),
                payment.getPgProvider(),
                payment.getPgTransactionId(),
                refund.getRefundAmount(),
                refund.getReason(),
                refund.getCancelAttempt(),
                refund.getExecutionVersion(),
                refund.getRecoveryMode(),
                inquireFirst));
    }

    private void refreshPolicy(Payment payment, Refund refund, LocalDateTime now) {
        if (refund.enterSlowIfDue(now, properties.slowAfter()))
            committedCounter("payment.refund.slow", payment.getId());
        if ((refund.getRecoveryMode() == RefundRecoveryMode.ACTIVE
                        || refund.getRecoveryMode() == RefundRecoveryMode.SLOW)
                && !refund.canCancelAt(now, properties.maxCancelAge())) {
            refund.verifyOnly("PG_CANCEL_KEY_WINDOW_EXCEEDED", now);
            committedCounter("payment.refund.verification.required", payment.getId());
        }
    }

    private LocalDateTime nextAttempt(Refund refund, LocalDateTime now) {
        long seconds = refund.getRecoveryMode() == RefundRecoveryMode.ACTIVE
                ? properties.claimSeconds(refund.getRecoveryAttempts())
                : properties.slowSeconds(
                        refund.getSlowAttempts(),
                        java.util.concurrent.ThreadLocalRandom.current().nextDouble());
        return now.plusSeconds(seconds).truncatedTo(ChronoUnit.MICROS);
    }

    private boolean owns(Refund refund, RefundClaim claim) {
        return refund.isAutomaticallyQueryable()
                && refund.getCancelAttempt() == claim.cancelAttempt()
                && refund.getExecutionVersion() == claim.executionVersion();
    }

    @Transactional
    public boolean authorizeCancel(RefundClaim claim) {
        Payment payment = payments.lockById(claim.paymentId()).orElseThrow();
        Refund refund = refunds.lockByPaymentId(payment.getId()).orElseThrow();
        if (!owns(refund, claim)) return false;
        refreshPolicy(payment, refund, LocalDateTime.now(clock));
        return refund.canCancelAt(LocalDateTime.now(clock), properties.maxCancelAge());
    }

    /** 조회 결과만으로 안전한 전액 취소가 확인되거나, 같은 키의 재요청이 허용되는지 결정한다. */
    @Transactional
    public boolean applyInquiry(RefundClaim claim, PgInquiry inquiry) {
        Payment payment = payments.lockById(claim.paymentId()).orElseThrow();
        Refund refund = refunds.lockByPaymentId(payment.getId()).orElseThrow();
        if (refund.getStatus() == RefundStatus.DONE) return false;
        if (validCanceled(payment, refund, inquiry)) {
            PgInquiry.Canceled canceled = (PgInquiry.Canceled) inquiry;
            refund.confirmCancellation(canceled.cancelId(), canceled.canceledAt());
            afterCompletion(payment, refund);
            return false;
        }
        if (!owns(refund, claim)) return false;
        LocalDateTime now = LocalDateTime.now(clock);
        refreshPolicy(payment, refund, now);
        if (inquiry instanceof PgInquiry.Approved approved) {
            if (approved.transactionId() != null
                    && approved.transactionId().equals(payment.getPgTransactionId())
                    && approved.amount() == refund.getRefundAmount()
                    && approved.approvedAt() != null) {
                if (refund.canCancelAt(now, properties.maxCancelAge())) return true;
                defer(refund, "PG_CANCEL_VERIFICATION_ONLY", now);
                return false;
            }
            verificationRequired(payment, refund, "PG_INCONSISTENT_APPROVAL", now);
        } else if (inquiry instanceof PgInquiry.Canceled || inquiry instanceof PgInquiry.Inconsistent) {
            verificationRequired(
                    payment,
                    refund,
                    inquiry instanceof PgInquiry.Inconsistent inconsistent
                            ? inconsistent.reason()
                            : "PG_INCONSISTENT_CANCELLATION",
                    now);
        } else {
            defer(refund, "PG_CANCEL_INQUIRY_UNKNOWN", now);
        }
        return false;
    }

    private void verificationRequired(Payment payment, Refund refund, String reason, LocalDateTime now) {
        boolean first = refund.getRecoveryMode() != RefundRecoveryMode.VERIFY_ONLY;
        refund.verifyOnly(reason, now);
        defer(refund, reason, now);
        if (first) committedCounter("payment.refund.verification.required", payment.getId());
    }

    private void defer(Refund refund, String reason, LocalDateTime now) {
        refund.deferRecovery(reason, nextAttempt(refund, now));
    }

    @Transactional
    public boolean apply(RefundClaim claim, PgResult result) {
        Payment payment = payments.lockById(claim.paymentId()).orElseThrow();
        Refund refund = refunds.lockByPaymentId(payment.getId()).orElseThrow();
        if (refund.getStatus() == RefundStatus.DONE) return false;
        boolean owned = owns(refund, claim);
        if (!owned) {
            if (result instanceof PgResult.Approved success && valid(success, refund.getRefundAmount())) {
                refund.requireCancellationVerification();
                if (refund.getRecoveryMode() == RefundRecoveryMode.PAUSED
                        && refund.getStatus() == RefundStatus.FAILED) {
                    refund.verifyOnly("STALE_CANCEL_SUCCESS_REQUIRES_VERIFICATION", LocalDateTime.now(clock));
                }
                committedCounter("payment.refund.stale.success.verification", payment.getId());
                return true;
            }
            return false;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (result instanceof PgResult.Approved success && valid(success, refund.getRefundAmount())) {
            finish(payment, refund, success.transactionId(), success.at());
        } else if (result instanceof PgResult.Rejected rejected && businessRejection(rejected)) {
            refund.reject(rejected.code(), "PG_CANCEL_REJECTED", now);
            publisher.refundFinalized(payment, refund);
            committedCounter("payment.refund.rejected", payment.getId());
        } else if (result instanceof PgResult.Approved
                || result instanceof PgResult.Unknown unknown && "PG_INVALID_CANCELLATION".equals(unknown.reason())) {
            verificationRequired(payment, refund, "PG_INCONSISTENT_CANCELLATION", now);
        } else {
            refreshPolicy(payment, refund, now);
            defer(refund, "PG_CANCEL_UNKNOWN", now);
        }
        return false;
    }

    @Transactional
    public void confirmCanceled(RefundClaim claim, PgInquiry inquiry) {
        Payment payment = payments.lockById(claim.paymentId()).orElseThrow();
        Refund refund = refunds.lockByPaymentId(payment.getId()).orElseThrow();
        if (refund.getStatus() == RefundStatus.DONE) return;
        if (validCanceled(payment, refund, inquiry)) {
            PgInquiry.Canceled canceled = (PgInquiry.Canceled) inquiry;
            refund.confirmCancellation(canceled.cancelId(), canceled.canceledAt());
            afterCompletion(payment, refund);
        }
    }

    private boolean validCanceled(Payment payment, Refund refund, PgInquiry inquiry) {
        return inquiry instanceof PgInquiry.Canceled canceled
                && java.util.Objects.equals(payment.getPgTransactionId(), canceled.transactionId())
                && canceled.transactionId() != null
                && canceled.cancelId() != null
                && !canceled.cancelId().isBlank()
                && canceled.cancelId().length() <= 200
                && canceled.amount() == refund.getRefundAmount()
                && canceled.remainingAmount() == 0
                && canceled.canceledAt() != null;
    }

    @Transactional(readOnly = true)
    public double oldestDueAgeSeconds() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime oldest = payments.oldestDueRefund(now);
        return oldest == null ? 0 : Math.max(0, Duration.between(oldest, now).toMillis() / 1000.0);
    }

    private Payment locked(String orderId) {
        return payments.lockByOrderId(orderId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));
    }

    private void finish(Payment payment, Refund refund, String cancelId, LocalDateTime at) {
        refund.complete(cancelId, at);
        afterCompletion(payment, refund);
    }

    private void afterCompletion(Payment payment, Refund refund) {
        if (payment.getStatus() == PaymentStatus.DONE) payment.cancel();
        publisher.refundFinalized(payment, refund);
        committedCounter("payment.refund.completed", payment.getId());
        if (refund.getRequestedAt() != null) {
            Duration elapsed = Duration.between(refund.getRequestedAt(), LocalDateTime.now(clock));
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    meters.timer("payment.refund.completion.duration")
                            .record(elapsed.isNegative() ? Duration.ZERO : elapsed);
                }
            });
        }
    }

    private static boolean valid(PgResult.Approved result, int amount) {
        return result.transactionId() != null
                && !result.transactionId().isBlank()
                && result.transactionId().length() <= 200
                && result.amount() == amount
                && result.at() != null;
    }

    private static boolean businessRejection(PgResult.Rejected rejected) {
        return rejected.code() != null
                && rejected.code().matches("[A-Za-z0-9_-]{1,80}")
                && !java.util.Set.of("PG_UNAVAILABLE", "PG_BUSY").contains(rejected.code());
    }

    private void committedCounter(String name, Long paymentId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                meters.counter(name).increment();
                if (!name.equals("payment.refund.completed")) {
                    log.warn("환불 후속 확인 필요. paymentId={}, event={}", paymentId, name);
                }
            }
        });
    }
}
