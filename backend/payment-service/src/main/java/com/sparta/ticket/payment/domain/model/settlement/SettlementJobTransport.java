package com.sparta.ticket.payment.domain.model.settlement;

/** 작업 생성 시 고정한 인수 경로. 설정 변경이 기존 작업의 소유자를 바꾸지 않는다. */
public enum SettlementJobTransport {
    DB,
    KAFKA
}
