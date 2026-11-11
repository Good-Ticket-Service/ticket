package com.sparta.ticket.payment.application.port.settlement;

import com.sparta.ticket.payment.domain.model.settlement.LedgerKey;

/** 새 금융 사실과 발행할 사건을 같은 로컬 트랜잭션으로 기록한다. */
public interface SettlementFactJournal {
    void append(LedgerKey key);
}
