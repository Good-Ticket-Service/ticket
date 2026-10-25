package com.sparta.ticket.fakepg.domain.model.transaction;

/** 토스 결제 상태 중 fake PG가 쓰는 값. */
public enum PgTransactionStatus {
    /** 사용자 인증이 끝나 승인을 기다린다. 이 상태로 조회되면 아직 돈이 빠지지 않았다. */
    READY,
    /** 승인 완료. */
    DONE,
    /** 전액 취소. */
    CANCELED,
    /** 승인이 거절됐다. */
    ABORTED,
    /** 인증 유효 시간 안에 승인 요청이 오지 않았다. */
    EXPIRED
}
