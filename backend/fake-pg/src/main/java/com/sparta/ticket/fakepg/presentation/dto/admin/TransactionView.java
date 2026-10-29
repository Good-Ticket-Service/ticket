package com.sparta.ticket.fakepg.presentation.dto.admin;

import com.sparta.ticket.fakepg.domain.model.transaction.PgTransaction;
import java.time.LocalDateTime;

/** 대사·정합성 검사에 쓰는 거래 기록. */
public record TransactionView(
        String provider,
        String paymentKey,
        String orderId,
        int amount,
        String status,
        LocalDateTime requestedAt,
        LocalDateTime approvedAt,
        LocalDateTime canceledAt,
        int canceledAmount,
        String cancelTransactionKey,
        String failureCode) {

    public static TransactionView from(PgTransaction transaction) {
        return new TransactionView(
                transaction.getProvider().name(),
                transaction.getPaymentKey(),
                transaction.getOrderId(),
                transaction.getAmount(),
                transaction.getStatus().name(),
                transaction.getRequestedAt(),
                transaction.getApprovedAt(),
                transaction.getCanceledAt(),
                transaction.canceledAmountOrZero(),
                transaction.getCancelTransactionKey(),
                transaction.getFailureCode());
    }
}
