package com.sparta.ticket.payment.domain.model.refund;

/** 업무 결과와 독립적인 실행 정책. VERIFY_ONLY는 조회를 계속하되 취소를 보내지 않는다. */
public enum RefundRecoveryMode {
    ACTIVE,
    SLOW,
    VERIFY_ONLY,
    PAUSED
}
