package com.sparta.ticket.payment.domain;

public enum RefundReason {
    USER_CANCEL,
    SCHEDULE_CANCELED,
    /** 결제는 승인됐는데 예매를 확정하지 못한 보상 환불. */
    CONFIRM_FAILED,
    /** FAILED로 확정한 뒤 발견한 늦은 승인이나 금액 불일치 승인을 돌려주는 자동 취소. */
    AUTO_VOID
}
