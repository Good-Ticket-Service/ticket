package com.sparta.ticket.payment.application.service.settlement;

import com.sparta.ticket.payment.application.service.observation.CommittedObservation;
import com.sparta.ticket.payment.domain.model.settlement.LedgerConflictException;
import com.sparta.ticket.payment.domain.model.settlement.LedgerKey;
import com.sparta.ticket.payment.domain.model.settlement.LedgerTransaction;
import com.sparta.ticket.payment.domain.repository.settlement.LedgerTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 원장 기록의 유일한 입구. 승인·환불·발행·지급 트랜잭션이 자기 트랜잭션 안에서 부른다(Task 20-2). */
@Component
@RequiredArgsConstructor
public class LedgerTransactions {

    private final LedgerTransactionRepository ledger;
    private final CommittedObservation observation;

    /**
     * 분개를 업무 키당 한 번만 기록한다. 같은 키에 같은 내용이면 ALREADY_POSTED, 다른 내용이면 LedgerConflictException이다.
     * Note: 호출자가 원천 행(payment·item·payout)을 먼저 잠근 트랜잭션 안에서만 부른다. 그래야 키 조회와 INSERT 사이에 경쟁자가 없다.
     * PostgreSQL은 UNIQUE 위반 뒤 트랜잭션 전체를 중단하므로, 위반을 잡아 같은 트랜잭션을 계속 쓰는 방식은 쓰지 않는다.
     * 그래서 자기 트랜잭션을 열지 않고(MANDATORY) 잠금 없는 호출을 즉시 실패시킨다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerPosting post(LedgerTransaction draft) {
        LedgerKey key = draft.key();
        var existing = ledger.findByKey(key.sourceType(), key.sourceId(), key.action());
        if (existing.isPresent()) {
            if (!existing.get().sameMeaningAs(draft)) throw new LedgerConflictException(key);
            return LedgerPosting.ALREADY_POSTED;
        }
        ledger.saveAndFlush(draft);
        observation.counter(
                "settlement.ledger.posted",
                "source_type",
                key.sourceType().name(),
                "action",
                key.action().name());
        return LedgerPosting.POSTED;
    }
}
