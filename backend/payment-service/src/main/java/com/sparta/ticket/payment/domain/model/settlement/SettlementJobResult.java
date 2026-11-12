package com.sparta.ticket.payment.domain.model.settlement;

/** DONE은 실행 종료를 뜻한다. UNPOSTED는 별도 계산 복구가 필요한 결과다. */
public enum SettlementJobResult {
    APPLIED,
    UNPOSTED,
    NO_FINANCIAL_EFFECT,
    LEGACY
}
