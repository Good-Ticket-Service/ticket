package com.sparta.ticket.fakepg.presentation.dto.toss;

import com.sparta.ticket.fakepg.domain.model.transaction.PgTransaction;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 토스 Payment 객체 중 payment-service가 쓰는 필드. 시각은 토스처럼 +09:00 오프셋을 붙인 ISO 8601이다.
 */
public record TossPaymentResponse(
        String mId,
        String version,
        String paymentKey,
        String type,
        String orderId,
        String orderName,
        String currency,
        String method,
        int totalAmount,
        int balanceAmount,
        String status,
        OffsetDateTime requestedAt,
        OffsetDateTime approvedAt,
        List<Cancel> cancels) {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    public static TossPaymentResponse from(PgTransaction transaction) {
        int canceledAmount = transaction.canceledAmountOrZero();
        List<Cancel> cancels = canceledAmount > 0
                ? List.of(new Cancel(
                        canceledAmount,
                        transaction.getCancelReason(),
                        offset(transaction.getCanceledAt()),
                        transaction.getCancelTransactionKey()))
                : null;
        return new TossPaymentResponse(
                "tosspayments",
                "2022-11-16",
                transaction.getPaymentKey(),
                "NORMAL",
                transaction.getOrderId(),
                transaction.getOrderName(),
                "KRW",
                "카드",
                transaction.getAmount(),
                transaction.getAmount() - canceledAmount,
                transaction.getStatus().name(),
                offset(transaction.getRequestedAt()),
                offset(transaction.getApprovedAt()),
                cancels);
    }

    private static OffsetDateTime offset(LocalDateTime time) {
        return time == null ? null : time.atZone(SEOUL).toOffsetDateTime();
    }

    public record Cancel(int cancelAmount, String cancelReason, OffsetDateTime canceledAt, String transactionKey) {}
}
