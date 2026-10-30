package com.sparta.ticket.payment.domain.model.settlement;

/** 지급 실패 사유. 모두 돈이 나가지 않은 확정 실패다. */
public enum PayoutFailure {
    /** 계좌·예금주 불일치. 판매자가 계좌를 고치면 retry(ACCOUNT_FIXED)할 수 있다. */
    ACCOUNT_INVALID,
    /** 지급대행이 원인을 밝히지 않고 거절했다. 자동 retry하지 않는다. */
    REJECTED,
    /** 지급대행이 요청 형식을 거절해 접수하지 않았다. 원인을 고친 뒤 운영 판단으로 retry(REQUEST_FIXED)한다. */
    REQUEST_INVALID
}
