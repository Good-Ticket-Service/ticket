package com.sparta.ticket.payment.domain;

public enum RefundStatus {
    /** 환불 요청을 받았는데 결제가 아직 IN_PROGRESS다. 결제가 확정되면 이어서 처리한다. */
    WAITING,
    /** PG 취소를 불렀거나 부를 차례이고 결과를 모른다. 복구 재개 지점이다. */
    REQUESTED,
    /** 취소 확정. */
    DONE,
    /** PG가 취소를 업무상 거절했다. 자동 재시도하지 않고 관리자가 다시 시도한다. */
    FAILED,
    /** WAITING이었는데 결제가 FAILED로 확정돼 환불할 돈이 없다. 늦은 승인이 발견되면 다시 REQUESTED가 된다. */
    SKIPPED
}
