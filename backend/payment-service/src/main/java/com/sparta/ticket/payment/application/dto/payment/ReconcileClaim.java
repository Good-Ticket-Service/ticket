package com.sparta.ticket.payment.application.dto.payment;

import com.sparta.ticket.payment.domain.model.pg.PgProvider;
import java.time.LocalDateTime;

public record ReconcileClaim(
        Long paymentId,
        String orderId,
        PgProvider provider,
        String transactionId,
        int checkpoint,
        LocalDateTime claimedUntil) {
    @Override
    public String toString() {
        return "ReconcileClaim[paymentId=" + paymentId + ", checkpoint=" + checkpoint + "]";
    }
}
