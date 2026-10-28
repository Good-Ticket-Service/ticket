package com.sparta.ticket.payment.application.dto.refund;

import com.sparta.ticket.payment.domain.model.payment.PaymentStatus;
import com.sparta.ticket.payment.domain.model.pg.PgProvider;
import com.sparta.ticket.payment.domain.model.refund.RefundRecoveryMode;
import com.sparta.ticket.payment.domain.model.refund.RefundStatus;
import java.time.LocalDateTime;
import java.util.List;

/** 운영 출력은 허용한 필드만 사용한다. PG 거래 키와 원문은 출력하지 않는다. */
public final class RefundOperation {
    private RefundOperation() {}

    public enum Action {
        RESUME,
        RETRY
    }

    public enum Reason {
        RESUME_OBSERVATION,
        ROOT_CAUSE_FIXED,
        AUTH_CONFIGURATION_FIXED
    }

    public enum Result {
        APPLIED,
        CONFIRMED_CANCELED,
        DENIED,
        STALE,
        PG_UNKNOWN
    }

    public record Request(
            String commandId,
            String orderId,
            Action action,
            Reason reason,
            RefundStatus expectedStatus,
            RefundRecoveryMode expectedMode,
            long expectedVersion,
            int expectedAttempt) {
        public Request {
            if (commandId == null
                    || !commandId.matches("[A-Za-z0-9_-]{8,80}")
                    || orderId == null
                    || orderId.isBlank()
                    || orderId.length() > 64
                    || action == null
                    || reason == null
                    || expectedStatus == null
                    || expectedMode == null
                    || expectedVersion < 0
                    || expectedAttempt < 0) throw new IllegalArgumentException("INVALID_COMMAND");
        }
    }

    public record View(
            Long refundId,
            String orderId,
            PaymentStatus paymentStatus,
            RefundStatus status,
            RefundRecoveryMode mode,
            PgProvider provider,
            com.sparta.ticket.payment.domain.model.refund.RefundReason refundReason,
            int amount,
            LocalDateTime rowCreatedAt,
            LocalDateTime requestedAt,
            LocalDateTime restrictionStartedAt,
            String restrictionReason,
            String lastError,
            String failureCode,
            LocalDateTime nextAttemptAt,
            LocalDateTime paymentNextAttemptAt,
            LocalDateTime paymentNextReconcileAt,
            LocalDateTime cancelKeyCreatedAt,
            long executionVersion,
            int cancelAttempt,
            boolean automaticallyQueryable,
            boolean automaticCancelAllowed,
            String guidance) {}

    public record Outcome(
            String commandId,
            Result result,
            String explanation,
            RefundStatus status,
            RefundRecoveryMode mode,
            long executionVersion,
            int cancelAttempt,
            LocalDateTime processedAt) {}

    public record Page(List<View> items, Long nextCursor) {}

    public record History(
            String commandId,
            String action,
            String actor,
            String reason,
            String result,
            String explanation,
            String expectedState,
            String beforeState,
            String afterState,
            String inquiry,
            LocalDateTime processedAt) {}

    public record Detail(View current, List<History> commands, String pgObservation, LocalDateTime observedAt) {}
}
