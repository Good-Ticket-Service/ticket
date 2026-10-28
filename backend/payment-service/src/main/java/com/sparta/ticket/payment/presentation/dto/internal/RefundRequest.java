package com.sparta.ticket.payment.presentation.dto.internal;

import com.sparta.ticket.payment.domain.model.refund.RefundReason;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

public record RefundRequest(@NotNull RefundReason reason) {
    @AssertTrue(message = "사용자 환불 사유만 허용한다")
    public boolean isAllowedReason() {
        return reason != RefundReason.AUTO_VOID;
    }
}
