package com.sparta.ticket.payment.domain.model.settlement;

public enum SettlementItemStatus {
    /** SALE: 정산 금액을 계산하지 못해 분개하지 않았다. 보충 잡이 다시 계산한다. */
    UNPOSTED,
    /** SALE: 분개했고 정산 가능일을 기다린다. */
    PENDING,
    /** SALE: 분개했지만 환불 진행·회차 시각 없음 등으로 정산서에 넣지 않는다. */
    HELD,
    /** SALE: 정산서에 들어갔다. 이후 환불은 차감으로 처리한다. */
    ON_STATEMENT,
    /** SALE: 정산서 발행 전에 전액 환불돼 반대 분개로 상쇄했다. */
    CANCELED,
    /** DEDUCTION: 아직 상계하지 못한 금액이 남았다. */
    OPEN,
    /** DEDUCTION: 모두 상계했다. */
    CLEARED
}
