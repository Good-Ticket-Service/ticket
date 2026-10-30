package com.sparta.ticket.payment.domain.model.settlement;

/** 정산서 상태. */
public enum StatementStatus {
    /** 지급할 금액이 있어 지급(READY)을 만들었다. */
    ISSUED,
    /** 상계로 지급액이 0이라 지급을 만들지 않았다. */
    NO_PAYOUT,
    /** 지급이 완료됐다. 지급 분개와 같은 트랜잭션에서 바뀐다. */
    PAID
}
