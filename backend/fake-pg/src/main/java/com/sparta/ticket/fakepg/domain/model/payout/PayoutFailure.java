package com.sparta.ticket.fakepg.domain.model.payout;

/** 지급대행 실패 사유. */
public enum PayoutFailure {
    /** 계좌·예금주 불일치. 판매자가 계좌를 고친 뒤 다시 지급한다. */
    ACCOUNT_INVALID,
    /** 그 밖의 지급대행 거절. 원인을 알 수 없어 자동 재지급하지 않는다. */
    REJECTED
}
