package com.sparta.ticket.payment.application.service.settlement;

/** 분개 기록 결과. 같은 업무를 다시 기록하면 ALREADY_POSTED다. */
public enum LedgerPosting {
    POSTED,
    ALREADY_POSTED
}
