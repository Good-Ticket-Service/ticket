package com.sparta.ticket.payment.application.service.refund;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.payment.application.dto.refund.RefundClaim;
import com.sparta.ticket.payment.application.dto.refund.RefundResult;
import com.sparta.ticket.payment.application.port.payment.PaymentResultPublisher;
import com.sparta.ticket.payment.application.service.payment.PaymentTransactions;
import com.sparta.ticket.payment.application.service.settlement.SettlementRefundTransactions;
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
    /** 실패 결제의 자동 취소 금액을 PG 조회로 바로잡는 최대 횟수. 넘으면 기존 업무 거절처럼 운영 인수로 넘긴다. */
    static final int MAX_VOID_CORRECTIONS = 3;

    /** 금액을 다시 확인하는 자동 취소는 저빈도 일정 대신 이 횟수까지 기본 백오프로 조회한다. */
    private static final int VOID_RECHECK_ACTIVE_ATTEMPTS = 5;

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final PaymentTransactions paymentTransactions;
    private final PaymentResultPublisher publisher;
    private final RefundRecoveryProperties properties;
    private final Clock clock;
    private final MeterRegistry meters;
    private final com.sparta.ticket.payment.application.service.observation.CommittedObservation observation;
    private final SettlementRefundTransactions settlementRefunds;

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
            case DONE -> {
                Refund requested =
                        refunds.saveAndFlush(Refund.requested(payment, reason, payment.getAmount(), now, now));
                settlementRefunds.refundAccepted(payment.getId(), requested.getId());
                yield RefundResult.from(requested);
            }
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
        // Note: 실패 결제의 자동 취소 금액은 검증에 실패했거나 늦게 온 응답에서 왔다. 첫 취소 전에도 PG 승인액을 조회로 확인한다.
        boolean inquireFirst = refund.getExecutionVersion() > 0
                || refund.getRecoveryMode() != RefundRecoveryMode.ACTIVE
                || payment.getStatus() == PaymentStatus.FAILED;
        observation.duration("payment.execution.claim.delay", refund.getNextAttemptAt(), "task", "refund");
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
        // Note: 조회 전용으로 바뀐 직후의 첫 재조회는 기본 백오프로 한다. 응답만 틀렸고 취소는 끝난 경우가 많아,
        // 저빈도 첫 간격(600초)을 기다리면 완료 확정이 늦어진다(#164 I1).
        boolean firstVerification =
                refund.getRecoveryMode() == RefundRecoveryMode.VERIFY_ONLY && refund.getSlowAttempts() == 0;
        long seconds = refund.getRecoveryMode() == RefundRecoveryMode.ACTIVE || firstVerification
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
            boolean sameApproval = approved.transactionId() != null
                    && approved.transactionId().equals(payment.getPgTransactionId())
                    && approved.approvedAt() != null;
            if (sameApproval && approved.amount() == refund.getRefundAmount()) {
                if (refund.canCancelAt(now, properties.maxCancelAge())) return true;
                if (voidRecheck(payment, refund)) deferVoidRecheck(refund, "PG_CANCEL_VERIFICATION_ONLY", now);
                else defer(refund, "PG_CANCEL_VERIFICATION_ONLY", now);
                return false;
            }
            if (sameApproval && voidAmountCorrectable(payment, refund, approved.amount())) {
                // Note: 다음 실행이 새 금액·새 키로 다시 조회한 뒤 취소한다. 지금 실행의 claim은 이전 금액·키를 들고 있다.
                refund.correctVoidAmount(approved.amount(), now);
                committedCounter("payment.refund.void.amount.corrected", payment.getId());
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

    private static boolean voidRecheck(Payment payment, Refund refund) {
        return payment.getStatus() == PaymentStatus.FAILED
                && Refund.VOID_CANCEL_REJECTED.equals(refund.getVerificationReason());
    }

    private static boolean voidAmountCorrectable(Payment payment, Refund refund, int approvedAmount) {
        return payment.getStatus() == PaymentStatus.FAILED
                && approvedAmount > 0
                && approvedAmount != refund.getRefundAmount()
                && refund.getCancelAttempt() < MAX_VOID_CORRECTIONS
                && refund.isVoidAmountCorrectable();
    }

    /** 거절 직후 금액 재확인은 저빈도 일정(최초 600초)을 기다리지 않고 기본 백오프로 몇 번 조회한다. */
    private void deferVoidRecheck(Refund refund, String reason, LocalDateTime now) {
        if (refund.getRecoveryAttempts() >= VOID_RECHECK_ACTIVE_ATTEMPTS) {
            defer(refund, reason, now);
            return;
        }
        refund.deferRecovery(
                reason,
                now.plusSeconds(properties.claimSeconds(refund.getRecoveryAttempts()))
                        .truncatedTo(ChronoUnit.MICROS));
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
        } else if (result instanceof PgResult.Rejected rejected
                && businessRejection(rejected)
                && payment.getStatus() == PaymentStatus.FAILED
                && refund.getCancelAttempt() < MAX_VOID_CORRECTIONS) {
            // Note: 자동 취소 금액이 틀린 응답에서 왔으면 PG가 거절한다. 멈추지 않고 승인액을 다시 조회한다(D20).
            refund.rejectForVoidRecheck(rejected.code(), now);
            deferVoidRecheck(refund, Refund.VOID_CANCEL_REJECTED, now);
            committedCounter("payment.refund.void.recheck", payment.getId());
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
        // 요청·recovery·조회 확정·운영 명령 모두 여기로 모인다. 실패 결제의 자동 취소는 SALE이 없어 정산이 아무것도 하지 않는다.
        settlementRefunds.refundCompleted(payment.getId(), refund.getId(), refund.getRefundedAt());
        publisher.refundFinalized(payment, refund);
        committedCounter("payment.refund.completed", payment.getId());
        if (refund.getCreatedAt() != null)
            observation.duration(
                    "payment.refund.record.completion.duration",
                    refund.getCreatedAt(),
                    "provider",
                    payment.getPgProvider().name());
        if (refund.getRequestedAt() != null) {
            observation.duration("payment.refund.completion.duration", refund.getRequestedAt());
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
                try {
                    meters.counter(name).increment();
                } catch (RuntimeException e) {
                    log.warn("커밋된 환불 결과의 계측 실패");
                }
                if (!name.equals("payment.refund.completed")) {
                    log.warn("환불 후속 확인 필요. paymentId={}, event={}", paymentId, name);
                }
            }
        });
    }
}
