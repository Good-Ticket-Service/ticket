package com.sparta.ticket.payment.domain.model.settlement;

/** 정산 반영 작업을 만든 사건. 반영은 사건이 아니라 그 결제의 현재 상태를 기준으로 하므로 어느 쪽이 먼저 처리돼도 결과가 같다. */
public enum SettlementJobReason {
    /** 결제 승인 확정. SALE과 판매 분개를 만든다. */
    PAYMENT_APPROVED,
    /** 환불 완료. 판매 분개를 뒤집거나 정산서 뒤면 차감(DEDUCTION)을 만든다. */
    REFUND_COMPLETED
}
