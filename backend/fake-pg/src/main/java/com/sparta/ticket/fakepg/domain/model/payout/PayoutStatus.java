package com.sparta.ticket.fakepg.domain.model.payout;

/** PG 지급대행 상태. 접수는 지급 완료가 아니다. */
public enum PayoutStatus {
    /** 지급 요청을 받았다. */
    ACCEPTED,
    /** 지급을 처리하고 있다. */
    PROCESSING,
    /** 지급대행이 송금을 마쳤다. 판매자 은행의 입금 반영을 보장하지는 않는다. */
    COMPLETED,
    /** 지급하지 못했다. 실패 코드가 함께 있다. */
    FAILED
}
