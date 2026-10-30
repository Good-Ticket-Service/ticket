package com.sparta.ticket.payment.domain.model.settlement;

/** 원천에 대한 분개 종류. SALE-POST, REFUND-REVERSE/RECEIVABLE, STATEMENT-OFFSET, PAYOUT-DONE만 쓴다. */
public enum LedgerAction {
    POST,
    REVERSE,
    RECEIVABLE,
    OFFSET,
    DONE
}
