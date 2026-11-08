package com.sparta.ticket.payment.domain.model.settlement;

public enum SettlementJobStatus {
    /** 처리 대기 또는 점유 중. 점유 중이면 next_attempt_at이 점유 기한이다. */
    PENDING,
    DONE,
    /** 반복 실패로 격리했다. 원인을 고친 뒤 운영자가 PENDING으로 되돌린다. 결제 확정과는 무관하다. */
    BLOCKED
}
