package com.sparta.ticket.payment.domain.model.payment;

/**
 * 예매가 FAILED 결제를 보고 다음 행동을 고르는 분류(docs/shared/internal-api/payment.md failureType). 예매는 failureCode가
 * 아니라 이 값만 본다.
 */
public enum PaymentFailureType {
    /** 다른 수단으로 다시 결제한다. */
    REJECTED,
    /** 잠시 후 같은 수단으로 다시 시도한다. */
    UNAVAILABLE,
    /** 선점이 끝났으면 만료, 아니면 다시 준비한다. */
    EXPIRED,
    /** 결제 안 됨으로 확정. 다시 결제할 수 있다. */
    NOT_APPROVED,
    /** 이 시도는 끝났다. 필요하면 새로 준비한다. */
    CLOSED;

    /** 저장된 실패 코드를 분류한다. 내부 코드 목록 밖의 값은 PG가 준 거절 코드이므로 REJECTED다. */
    public static PaymentFailureType of(String failureCode) {
        if (failureCode == null) {
            return null;
        }
        PaymentFailureCode code;
        try {
            code = PaymentFailureCode.valueOf(failureCode);
        } catch (IllegalArgumentException e) {
            return REJECTED;
        }
        return switch (code) {
            case SUPERSEDED, ABANDONED, READY_FAILED -> CLOSED;
            case PG_UNAVAILABLE, PG_BUSY, PG_UNRESOLVED -> UNAVAILABLE;
            case EXPIRED -> EXPIRED;
            case NOT_APPROVED, CANCELED_AT_PG -> NOT_APPROVED;
            case INVALID_AUTH_TOKEN, AMOUNT_MISMATCH -> REJECTED;
        };
    }
}
