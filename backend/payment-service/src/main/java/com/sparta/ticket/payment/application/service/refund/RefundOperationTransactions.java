package com.sparta.ticket.payment.application.service.refund;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.payment.application.dto.refund.RefundOperation;
import com.sparta.ticket.payment.application.port.payment.RefundOperationReader;
import com.sparta.ticket.payment.domain.exception.payment.PaymentErrorCode;
import com.sparta.ticket.payment.domain.model.payment.Payment;
import com.sparta.ticket.payment.domain.model.payment.PaymentStatus;
import com.sparta.ticket.payment.domain.model.pg.PgInquiry;
import com.sparta.ticket.payment.domain.model.pg.PgProvider;
import com.sparta.ticket.payment.domain.model.refund.Refund;
import com.sparta.ticket.payment.domain.model.refund.RefundOperationCommand;
import com.sparta.ticket.payment.domain.model.refund.RefundRecoveryMode;
import com.sparta.ticket.payment.domain.model.refund.RefundStatus;
import com.sparta.ticket.payment.domain.repository.payment.PaymentRepository;
import com.sparta.ticket.payment.domain.repository.refund.RefundOperationRepository;
import com.sparta.ticket.payment.domain.repository.refund.RefundRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class RefundOperationTransactions {
    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final RefundOperationRepository commands;
    private final RefundOperationReader reader;
    private final RefundRecoveryProperties properties;
    private final RefundTransactions refundTransactions;
    private final Clock clock;

    /** 외부 조회에 사용할 내부 스냅샷이다. 출력 DTO로 사용하지 않는다. */
    public record Snapshot(
            Long paymentId,
            Long refundId,
            PgProvider provider,
            String transactionId,
            int amount,
            String state,
            PaymentStatus paymentStatus,
            LocalDateTime keyCreatedAt,
            String restrictionReason,
            String failureCode,
            boolean blocked) {
        @Override
        public String toString() {
            return "Snapshot[REDACTED]";
        }
    }

    @Transactional(readOnly = true)
    public Snapshot snapshot(String orderId) {
        Payment p = payment(orderId, false);
        return snapshot(p, refund(p, false));
    }

    @Transactional(readOnly = true)
    public Optional<RefundOperation.Outcome> previous(String id, String hash) {
        return commands.findById(id).map(command -> replay(command, hash));
    }

    @Transactional
    public RefundOperation.Outcome apply(
            RefundOperation.Request request, String hash, String actor, Snapshot snapshot, PgInquiry inquiry) {
        Payment p = payment(request.orderId(), true);
        Refund r = refund(p, true);
        var existing = commands.findById(request.commandId());
        if (existing.isPresent()) return replay(existing.get(), hash);
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        String before = state(r);
        RefundOperation.Result result;
        String explanation;
        if (!snapshot(p, r).equals(snapshot) || !expected(request, r)) {
            result = RefundOperation.Result.STALE;
            explanation = "STATE_CHANGED_INSPECT_AGAIN";
        } else if (r.getStatus() == RefundStatus.DONE
                || r.getStatus() == RefundStatus.SKIPPED
                || r.getStatus() == RefundStatus.WAITING) {
            result = RefundOperation.Result.DENIED;
            explanation = "NOT_AN_OPERABLE_REFUND";
        } else if (validCanceled(p, r, inquiry)) {
            // Note: 완료 저장·후속 발행·완료 계측을 기존 경로와 공유한다.
            refundTransactions.confirmCanceled(
                    new com.sparta.ticket.payment.application.dto.refund.RefundClaim(
                            p.getId(),
                            p.getOrderId(),
                            p.getPgProvider(),
                            p.getPgTransactionId(),
                            r.getRefundAmount(),
                            r.getReason(),
                            r.getCancelAttempt(),
                            r.getExecutionVersion(),
                            r.getRecoveryMode(),
                            true),
                    inquiry);
            result = RefundOperation.Result.CONFIRMED_CANCELED;
            explanation = "PG_FULL_CANCELLATION_CONFIRMED";
        } else if (request.action() == RefundOperation.Action.RESUME
                && r.isRecoveryBlocked()
                && r.getRecoveryMode() == RefundRecoveryMode.PAUSED) {
            // Note: 조회 불명이어도 조회 전용 재개는 가능하다. 취소 권한은 주지 않는다.
            r.resumeVerification(now);
            result = RefundOperation.Result.APPLIED;
            explanation = "VERIFICATION_RESUMED_NO_CANCEL_PERMISSION";
        } else if (inquiry instanceof PgInquiry.Unknown || inquiry instanceof PgInquiry.NotFound) {
            result = RefundOperation.Result.PG_UNKNOWN;
            explanation = "PG_RESULT_UNCONFIRMED_NO_NEW_KEY";
        } else if (request.action() == RefundOperation.Action.RETRY
                && validApproval(p, r, inquiry)
                && safeRetry(request, p, r, now)) {
            if (r.getStatus() == RefundStatus.FAILED) r.retryByAdmin(now);
            else r.retrySameKey(now);
            result = RefundOperation.Result.APPLIED;
            explanation = "SCHEDULED_FOR_RECOVERY_NOT_REFUNDED";
        } else {
            result = RefundOperation.Result.DENIED;
            explanation = "RETRY_POLICY_DENIED_CHECK_PG_AND_ROOT_CAUSE";
        }
        var command = new RefundOperationCommand(
                request.commandId(),
                hash,
                p.getId(),
                r.getId(),
                request.action().name(),
                actor,
                request.reason().name(),
                result.name(),
                explanation,
                request.expectedStatus() + ":" + request.expectedMode() + ":" + request.expectedVersion() + ":"
                        + request.expectedAttempt(),
                before,
                state(r),
                observation(inquiry),
                now);
        commands.saveAndFlush(command);
        return outcome(command);
    }

    private boolean safeRetry(RefundOperation.Request request, Payment p, Refund r, LocalDateTime now) {
        if (r.getCancelKeyCreatedAt() == null
                || now.isBefore(r.getCancelKeyCreatedAt())
                || !now.isBefore(r.getCancelKeyCreatedAt().plus(properties.maxCancelAge()))) return false;
        if (r.getStatus() == RefundStatus.REQUESTED && r.getRecoveryMode() == RefundRecoveryMode.VERIFY_ONLY) {
            return request.reason() == RefundOperation.Reason.ROOT_CAUSE_FIXED
                    && java.util.Set.of("PG_INCONSISTENT_APPROVAL", "PG_TRANSACTION_MISMATCH")
                            .contains(Objects.toString(r.getVerificationReason(), ""));
        }
        // Note: 명시적인 인증 거절 후 설정 수정만 초기 허용한다. 일반 거절·불명·취소 불가를 새 키로 우회하지 않는다.
        return request.reason() == RefundOperation.Reason.AUTH_CONFIGURATION_FIXED
                && r.getStatus() == RefundStatus.FAILED
                && (r.getRecoveryMode() == RefundRecoveryMode.PAUSED
                        || r.getRecoveryMode() == RefundRecoveryMode.VERIFY_ONLY)
                && ((p.getPgProvider() == PgProvider.TOSS && "UNAUTHORIZED_KEY".equals(r.getFailureCode()))
                        || (p.getPgProvider() == PgProvider.KAKAO && "-401".equals(r.getFailureCode())));
    }

    @Transactional(readOnly = true)
    public RefundOperation.View inspect(String orderId) {
        Payment p = payment(orderId, false);
        return view(p, refund(p, false));
    }

    @Transactional(readOnly = true)
    public RefundOperation.Page list(
            long after,
            int limit,
            RefundStatus status,
            RefundRecoveryMode mode,
            PgProvider provider,
            LocalDateTime before) {
        if (after < 0 || limit < 1 || limit > 100) throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        List<Long> ids = reader.paymentIds(after, limit + 1, status, mode, provider, before);
        var items = new ArrayList<RefundOperation.View>();
        for (Long id : ids.stream().limit(limit).toList()) {
            Payment p = payments.findById(id).orElseThrow();
            Refund r = refund(p, false);
            // Note: READ COMMITTED의 후속 조회에서 완료된 행은 목록에 섞지 않는다.
            if (r.getStatus() != RefundStatus.DONE && r.getStatus() != RefundStatus.SKIPPED) items.add(view(p, r));
        }
        Long cursor = ids.size() > limit
                ? refunds.findByPaymentId(ids.get(limit - 1)).orElseThrow().getId()
                : null;
        return new RefundOperation.Page(List.copyOf(items), cursor);
    }

    @Transactional(readOnly = true)
    public List<RefundOperation.History> history(String orderId) {
        Payment p = payment(orderId, false);
        Refund r = refund(p, false);
        return commands.findFirst20ByRefundIdOrderByProcessedAtDescCommandIdDesc(r.getId()).stream()
                .map(c -> new RefundOperation.History(
                        c.getCommandId(),
                        c.getAction(),
                        c.getActor(),
                        c.getReason(),
                        c.getResult(),
                        c.getExplanation(),
                        c.getExpectedState(),
                        c.getBeforeState(),
                        c.getAfterState(),
                        c.getInquiry(),
                        c.getProcessedAt()))
                .toList();
    }

    private RefundOperation.View view(Payment p, Refund r) {
        boolean unfinished = r.getStatus() != RefundStatus.DONE && r.getStatus() != RefundStatus.SKIPPED;
        String reason = unfinished && (r.getRecoveryMode() == RefundRecoveryMode.VERIFY_ONLY || r.isRecoveryBlocked())
                ? Objects.toString(r.getVerificationReason(), Objects.toString(r.getFailureCode(), "UNKNOWN"))
                : null;
        reason = safeCode(reason);
        String guidance = !unfinished
                ? "TERMINAL_NO_OPERATION"
                : r.getStatus() == RefundStatus.WAITING
                        ? "WAITING_FOR_PAYMENT_RESULT"
                        : r.getRecoveryMode() == RefundRecoveryMode.PAUSED
                                ? "INSPECT_ROOT_CAUSE_RESUME_READ_ONLY_OR_VALIDATED_RETRY"
                                : r.getRecoveryMode() == RefundRecoveryMode.VERIFY_ONLY
                                        ? "READ_ONLY_CHECK_ROOT_CAUSE_AND_KEY_WINDOW"
                                        : "AUTOMATIC_RECOVERY_RUNNING";
        return new RefundOperation.View(
                r.getId(),
                p.getOrderId(),
                p.getStatus(),
                r.getStatus(),
                r.getRecoveryMode(),
                p.getPgProvider(),
                r.getReason(),
                r.getRefundAmount(),
                r.getCreatedAt(),
                r.getRequestedAt(),
                r.getRestrictionStartedAt(),
                reason,
                safeCode(r.getLastError()),
                safeCode(r.getFailureCode()),
                r.getNextAttemptAt(),
                p.getNextAttemptAt(),
                p.getNextReconcileAt(),
                r.getCancelKeyCreatedAt(),
                r.getExecutionVersion(),
                r.getCancelAttempt(),
                r.isAutomaticallyQueryable(),
                r.canCancelAt(LocalDateTime.now(clock), properties.maxCancelAge()),
                guidance);
    }

    private Payment payment(String order, boolean lock) {
        return (lock ? payments.lockByOrderId(order) : payments.findByOrderId(order))
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));
    }

    private Refund refund(Payment p, boolean lock) {
        return (lock ? refunds.lockByPaymentId(p.getId()) : refunds.findByPaymentId(p.getId()))
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.REFUND_NOT_FOUND));
    }

    private static Snapshot snapshot(Payment p, Refund r) {
        return new Snapshot(
                p.getId(),
                r.getId(),
                p.getPgProvider(),
                p.getPgTransactionId(),
                r.getRefundAmount(),
                state(r),
                p.getStatus(),
                r.getCancelKeyCreatedAt(),
                r.getVerificationReason(),
                r.getFailureCode(),
                r.isRecoveryBlocked());
    }

    private static boolean expected(RefundOperation.Request request, Refund r) {
        return request.expectedStatus() == r.getStatus()
                && request.expectedMode() == r.getRecoveryMode()
                && request.expectedVersion() == r.getExecutionVersion()
                && request.expectedAttempt() == r.getCancelAttempt();
    }

    private static String state(Refund r) {
        return r.getStatus() + ":" + r.getRecoveryMode() + ":" + r.getExecutionVersion() + ":" + r.getCancelAttempt();
    }

    private static RefundOperation.Outcome replay(RefundOperationCommand command, String hash) {
        if (!command.getInputHash().equals(hash)) throw new BusinessException(PaymentErrorCode.REFUND_COMMAND_CONFLICT);
        return outcome(command);
    }

    private static RefundOperation.Outcome outcome(RefundOperationCommand c) {
        String[] s = c.getAfterState().split(":");
        return new RefundOperation.Outcome(
                c.getCommandId(),
                RefundOperation.Result.valueOf(c.getResult()),
                c.getExplanation(),
                RefundStatus.valueOf(s[0]),
                RefundRecoveryMode.valueOf(s[1]),
                Long.parseLong(s[2]),
                Integer.parseInt(s[3]),
                c.getProcessedAt());
    }

    public static String observation(PgInquiry inquiry) {
        if (inquiry instanceof PgInquiry.Approved) return "APPROVED";
        if (inquiry instanceof PgInquiry.Canceled) return "CANCELED_REQUIRES_VALIDATION";
        if (inquiry instanceof PgInquiry.Inconsistent) return "INCONSISTENT";
        if (inquiry instanceof PgInquiry.NotFound) return "NOT_FOUND";
        return "UNKNOWN";
    }

    private static boolean validApproval(Payment p, Refund r, PgInquiry inquiry) {
        return inquiry instanceof PgInquiry.Approved a
                && a.transactionId() != null
                && a.transactionId().equals(p.getPgTransactionId())
                && a.amount() == r.getRefundAmount()
                && a.approvedAt() != null;
    }

    private static boolean validCanceled(Payment p, Refund r, PgInquiry inquiry) {
        return inquiry instanceof PgInquiry.Canceled c
                && c.transactionId() != null
                && c.transactionId().equals(p.getPgTransactionId())
                && c.amount() == r.getRefundAmount()
                && c.remainingAmount() == 0
                && c.canceledAt() != null
                && c.cancelId() != null
                && !c.cancelId().isBlank()
                && c.cancelId().length() <= 200;
    }

    private static String safeCode(String value) {
        return value == null ? null : value.matches("[A-Za-z0-9_-]{1,80}") ? value : "UNCLASSIFIED";
    }
}
