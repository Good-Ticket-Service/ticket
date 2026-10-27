package com.sparta.ticket.payment.domain.model.payment;

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
    /** PG 승인 호출 후 결과 확정 시한(1분) 안에 결과를 알지 못했다. 늦은 승인은 최소 대사가 찾아 자동 취소한다. */
    PG_UNRESOLVED,
    AMOUNT_MISMATCH,
    CANCELED_AT_PG
}
