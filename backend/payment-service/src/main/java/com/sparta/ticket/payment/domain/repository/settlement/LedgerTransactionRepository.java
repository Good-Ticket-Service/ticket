package com.sparta.ticket.payment.domain.repository.settlement;

import com.sparta.ticket.payment.domain.model.settlement.LedgerAction;
import com.sparta.ticket.payment.domain.model.settlement.LedgerSourceType;
import com.sparta.ticket.payment.domain.model.settlement.LedgerTransaction;
import java.util.Optional;

/** 원장은 추가 전용이라 저장과 업무 키 조회만 둔다. 수정·삭제 메서드를 만들지 않는다. */
public interface LedgerTransactionRepository {

    LedgerTransaction saveAndFlush(LedgerTransaction transaction);

    /** 업무 키로 분개를 줄과 함께 읽는다. 키마다 하나다(UNIQUE). */
    Optional<LedgerTransaction> findByKey(LedgerSourceType sourceType, Long sourceId, LedgerAction action);
}
