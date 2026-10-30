package com.sparta.ticket.fakepg.domain.model.pg;

/** 장애를 따로 걸 수 있는 PG API 단위. */
public enum PgOperation {
    /** 결제 준비. 카카오만 PG를 부른다. 토스에 건 READY 장애는 쓰이지 않는다. */
    READY,
    CONFIRM,
    INQUIRE,
    CANCEL,
    /** 판매자 정산 지급대행 요청. 토스형 지급대행으로 보고 TOSS 장애 설정을 쓴다. */
    PAYOUT,
    /** 지급대행 결과 조회. */
    PAYOUT_INQUIRE
}
