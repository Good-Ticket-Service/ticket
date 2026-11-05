package com.sparta.ticket.payment.application.port.settlement;

import com.sparta.ticket.payment.domain.model.settlement.LedgerTransaction;

/** 호출자 트랜잭션에서 원장 헤더와 균형이 검증된 모든 분개 줄을 함께 추가한다. */
public interface LedgerJournalWriter {
    void append(LedgerTransaction journal);
}
