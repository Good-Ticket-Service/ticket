package com.sparta.ticket.payment.domain.model.payment;

public enum PaymentStatus {
    /** 금액·쿠폰이 확정됐고 승인 요청을 기다린다. PG 승인은 아직 부르지 않았다. */
    READY,
    /** PG 승인을 불렀고 결과를 모른다. 복구 재개 지점이다. */
    IN_PROGRESS,
    /** 승인 확정. */
    DONE,
    /** 돈이 오가지 않음으로 확정. 늦은 승인이 발견되면 자동 취소 환불이 붙는다. */
    FAILED,
    /** 전액 환불 완료. */
    CANCELED
}
