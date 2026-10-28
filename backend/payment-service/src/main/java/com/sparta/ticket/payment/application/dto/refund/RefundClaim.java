package com.sparta.ticket.payment.application.dto.refund;

import com.sparta.ticket.payment.domain.model.pg.PgProvider;
import com.sparta.ticket.payment.domain.model.refund.RefundReason;
import com.sparta.ticket.payment.domain.model.refund.RefundRecoveryMode;

public record RefundClaim(
        Long paymentId,
        String orderId,
        PgProvider provider,
        String transactionId,
        int amount,
        RefundReason reason,
        int cancelAttempt,
        long executionVersion,
        RefundRecoveryMode mode,
        boolean inquireFirst) {
    public String cancelKey() {
        return orderId + ":cancel:" + cancelAttempt;
    }

    @Override
    public String toString() {
        return "RefundClaim[paymentId=" + paymentId + ", executionVersion=" + executionVersion + "]";
    }
}
