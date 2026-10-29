package com.sparta.ticket.fakepg.domain.model.fault;

/**
 * 성공 응답을 어떻게 망가뜨릴지. PG가 처리와 기록은 정상으로 끝냈는데 호출자가 받는 본문만 잘못된 상황을 만든다.
 * 호출자가 응답의 거래 정체성·금액을 검증하는지 시험하는 용도다.
 */
public enum TamperMode {
    /** 거래 키·금액·시각 필드를 뺀다. */
    MISSING_FIELDS,
    /** 거래 키와 주문번호를 다른 거래의 값으로 바꾼다. */
    OTHER_TRANSACTION,
    /** 금액 필드에 1,000원을 더한다. */
    WRONG_AMOUNT
}
