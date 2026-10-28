package com.sparta.ticket.reservation.booking.infrastructure.payment;

/** POST /internal/payments/{orderId}/confirm 요청. 인증값은 로그에 남지 않게 toString에서 가린다. */
public record PaymentConfirmRequest(String pgAuthToken, int amount) {

    @Override
    public String toString() {
        return "PaymentConfirmRequest[pgAuthToken=REDACTED, amount=" + amount + "]";
    }
}
