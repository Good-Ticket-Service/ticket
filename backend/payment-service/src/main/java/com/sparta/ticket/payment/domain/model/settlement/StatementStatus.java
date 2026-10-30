package com.sparta.ticket.payment.domain.model.settlement;

/** 정산서 상태. 지급 확정(PAID)은 지급 실행 작업에서 추가한다. */
public enum StatementStatus {
    /** 지급할 금액이 있어 지급(READY)을 만들었다. */
    ISSUED,
    /** 상계로 지급액이 0이라 지급을 만들지 않았다. */
    NO_PAYOUT
}
