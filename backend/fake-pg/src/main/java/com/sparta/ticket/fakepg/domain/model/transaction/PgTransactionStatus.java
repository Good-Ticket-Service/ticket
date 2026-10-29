package com.sparta.ticket.fakepg.domain.model.transaction;

/** 토스 결제 상태 중 fake PG가 쓰는 값. */
public enum PgTransactionStatus {
    /** 사용자 인증이 끝나 승인을 기다린다. 이 상태로 조회되면 아직 돈이 빠지지 않았다. */
    READY,
    /** 승인 완료. */
    DONE,
    /** 전액 취소. */
    CANCELED,
    /** 일부 금액만 취소됐다. 우리 API는 부분 취소를 받지 않으므로 관리 API의 외부 취소로만 생긴다. */
    PARTIAL_CANCELED,
    /** 승인이 거절됐다. */
    ABORTED,
    /** 인증 유효 시간 안에 승인 요청이 오지 않았다. */
    EXPIRED
}
