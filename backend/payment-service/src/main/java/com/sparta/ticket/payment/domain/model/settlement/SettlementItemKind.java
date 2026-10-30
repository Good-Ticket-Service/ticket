package com.sparta.ticket.payment.domain.model.settlement;

public enum SettlementItemKind {
    /** 승인된 결제 1건의 판매자 몫. 결제마다 하나. */
    SALE,
    /** 정산서 발행 뒤 환불된 판매의 판매자 몫을 돌려받을 차감. 환불마다 하나. */
    DEDUCTION
}
