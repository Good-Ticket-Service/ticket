package com.sparta.ticket.payment.application.dto.payment;

import com.sparta.ticket.payment.domain.model.pg.PgProvider;
import java.time.LocalDateTime;

/** 점유 시각은 오래된 조회의 재예약을 막는 비교값이다. 외부 과금의 fencing을 보장하지 않는다. */
public record RecoveryClaim(
        Long paymentId, String orderId, PgProvider provider, String transactionId, LocalDateTime claimedUntil) {
    @Override
    public String toString() {
        return "RecoveryClaim[paymentId=" + paymentId + "]";
    }
}
