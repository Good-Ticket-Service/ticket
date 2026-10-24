package com.sparta.ticket.payment.domain;

/** payment가 정하는 FAILED 사유. PG가 준 거절 코드는 이 목록 밖의 문자열로 그대로 저장한다. */
public enum PaymentFailureCode {
    SUPERSEDED,
    READY_FAILED,
    EXPIRED,
    ABANDONED,
    PG_UNAVAILABLE,
    PG_BUSY,
    INVALID_AUTH_TOKEN,
    NOT_APPROVED,
    AMOUNT_MISMATCH,
    CANCELED_AT_PG,
    MANUAL
}
