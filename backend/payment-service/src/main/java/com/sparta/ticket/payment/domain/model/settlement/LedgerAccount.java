package com.sparta.ticket.payment.domain.model.settlement;

/**
 * 정산 원장 계정(settlement-design 4.5절). 선언 순서가 분개 줄 순서다.
 * PG_CLEARING·PAYOUT_CLEARING은 추적용이며 실제 은행 현금이 아니다.
 */
public enum LedgerAccount {
    PG_CLEARING,
    DISCOUNT_EXPENSE,
    SELLER_RECEIVABLE,
    SELLER_PAYABLE,
    COMMISSION_REVENUE,
    BOOKING_FEE_REVENUE,
    VAT_PAYABLE,
    PAYOUT_CLEARING
}
