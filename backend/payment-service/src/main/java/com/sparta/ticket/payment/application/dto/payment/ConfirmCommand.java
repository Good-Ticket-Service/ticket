package com.sparta.ticket.payment.application.dto.payment;

/** 인증값은 요청 처리 중에만 사용하며 기본 toString으로 노출하지 않는다. */
public record ConfirmCommand(String pgAuthToken, int amount) {
    @Override
    public String toString() {
        return "ConfirmCommand[pgAuthToken=REDACTED, amount=" + amount + "]";
    }
}
