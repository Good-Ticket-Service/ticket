package com.sparta.ticket.payment.domain.model.settlement;

/** 분개를 만든 업무 원천. source_id는 각각 결제·환불·정산서·지급 ID다. */
public enum LedgerSourceType {
    SALE,
    REFUND,
    STATEMENT,
    PAYOUT
}
