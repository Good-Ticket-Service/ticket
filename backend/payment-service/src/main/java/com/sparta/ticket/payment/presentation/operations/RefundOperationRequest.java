package com.sparta.ticket.payment.presentation.operations;

import com.sparta.ticket.payment.application.dto.refund.RefundOperation;
import com.sparta.ticket.payment.domain.model.refund.RefundRecoveryMode;
import com.sparta.ticket.payment.domain.model.refund.RefundStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

/** 실행자와 명령 종류는 요청 본문에서 받지 않는다. */
public record RefundOperationRequest(
        @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{8,80}") String commandId,
        @NotNull RefundOperation.Reason reason,
        @NotNull RefundStatus expectedStatus,
        @NotNull RefundRecoveryMode expectedMode,
        @NotNull @PositiveOrZero Long expectedVersion,
        @NotNull @PositiveOrZero Integer expectedAttempt) {
    RefundOperation.Request toCommand(String orderId, RefundOperation.Action action) {
        return new RefundOperation.Request(
                commandId, orderId, action, reason, expectedStatus, expectedMode, expectedVersion, expectedAttempt);
    }
}
