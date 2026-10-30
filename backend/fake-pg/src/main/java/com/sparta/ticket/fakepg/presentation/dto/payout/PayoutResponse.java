package com.sparta.ticket.fakepg.presentation.dto.payout;

import com.sparta.ticket.fakepg.domain.model.payout.PayoutFailure;
import com.sparta.ticket.fakepg.domain.model.payout.PayoutStatus;
import com.sparta.ticket.fakepg.domain.model.payout.PgPayout;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** 지급대행 응답. 시각은 +09:00 오프셋을 붙인다. 확정 전에는 실패 코드·확정 시각이 없다. */
public record PayoutResponse(
        String payoutId,
        String idempotencyKey,
        String sellerRef,
        String accountRef,
        long amount,
        PayoutStatus status,
        PayoutFailure failureCode,
        OffsetDateTime requestedAt,
        OffsetDateTime decidedAt) {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    public static PayoutResponse from(PgPayout payout) {
        return new PayoutResponse(
                payout.getPayoutId(),
                payout.getIdempotencyKey(),
                payout.getSellerRef(),
                payout.getAccountRef(),
                payout.getAmount(),
                payout.getStatus(),
                payout.visibleFailureCode(),
                kst(payout.getRequestedAt()),
                kst(payout.getDecidedAt()));
    }

    private static OffsetDateTime kst(LocalDateTime time) {
        return time == null ? null : time.atOffset(KST);
    }
}
