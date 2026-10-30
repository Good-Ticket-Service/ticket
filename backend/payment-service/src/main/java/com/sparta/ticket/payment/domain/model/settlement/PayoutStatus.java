package com.sparta.ticket.payment.domain.model.settlement;

/** 지급 상태. 실행·결과 상태(REQUESTED·DONE·FAILED)는 지급 실행 작업에서 추가한다. */
public enum PayoutStatus {
    /** 외부 이체 전. */
    READY
}
