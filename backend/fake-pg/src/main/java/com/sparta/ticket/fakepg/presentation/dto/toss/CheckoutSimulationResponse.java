package com.sparta.ticket.fakepg.presentation.dto.toss;

import com.sparta.ticket.fakepg.domain.model.transaction.PgTransaction;
import java.time.LocalDateTime;

/** 결제 인증 시뮬레이션 결과. 실제 토스에서는 결제창 인증 후 successUrl로 받는 값이다. */
public record CheckoutSimulationResponse(String paymentKey, String orderId, int amount, LocalDateTime authExpiresAt) {

    public static CheckoutSimulationResponse from(PgTransaction transaction) {
        return new CheckoutSimulationResponse(
                transaction.getPaymentKey(),
                transaction.getOrderId(),
                transaction.getAmount(),
                transaction.getAuthExpiresAt());
    }
}
