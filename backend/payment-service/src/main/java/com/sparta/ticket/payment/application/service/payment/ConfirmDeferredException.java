package com.sparta.ticket.payment.application.service.payment;

/**
 * 메시지 대체 승인(#422)이 PG별 자리나 PG 허가를 얻지 못했다. claim하지 않아 결제는 READY다.
 * 호출자는 {@code Retry-After} 뒤 같은 내용으로 다시 보낸다.
 */
public class ConfirmDeferredException extends RuntimeException {
    private final long retryAfterSeconds;

    public ConfirmDeferredException(long retryAfterSeconds) {
        super("CONFIRM_DEFERRED", null, false, false);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
