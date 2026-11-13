package com.sparta.ticket.payment.application.dto.payment;

import java.time.OffsetDateTime;

/**
 * 인증값은 요청 처리 중에만 사용하며 기본 toString으로 노출하지 않는다.
 * {@code confirmBefore}가 있으면 메시지 대체 승인이다(#422). Outbox 메시지의 값을 그대로 받는다.
 */
public record ConfirmCommand(String pgAuthToken, int amount, OffsetDateTime confirmBefore) {
    public ConfirmCommand(String pgAuthToken, int amount) {
        this(pgAuthToken, amount, null);
    }

    public boolean fallback() {
        return confirmBefore != null;
    }

    @Override
    public String toString() {
        return "ConfirmCommand[pgAuthToken=REDACTED, amount=" + amount + ", confirmBefore=" + confirmBefore + "]";
    }
}
