package com.sparta.ticket.payment.domain.model.settlement;

/** 정산 금액을 계산하지 못한 이유. 결제를 실패시키지 않고 정산 항목에 남겨 대조·경보로 드러낸다. */
public enum SettlementFailure {
    /** 정가가 0 이하이거나 할인·예매수수료가 음수다. */
    INVALID_INPUT,
    /** 할인이 정가를 넘는다. */
    DISCOUNT_EXCEEDS_GROSS,
    /** 정가 − 할인 + 예매수수료가 결제 금액과 다르다. 결제 금액을 보정하지 않는다. */
    PAID_AMOUNT_MISMATCH,
    /** 계산 결과가 건별 INT 범위를 넘거나 판매자 몫이 음수다. */
    OUT_OF_RANGE,
    /** 승인 시각에 적용할 정산 정책 버전이 없다. */
    POLICY_NOT_FOUND
}
