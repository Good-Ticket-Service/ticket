package com.sparta.ticket.payment.domain.repository.settlement;

import com.sparta.ticket.payment.domain.model.settlement.Payout;
import com.sparta.ticket.payment.domain.model.settlement.SettlementStatement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** 지급 실행이 쓰는 저장소. 잠금 순서는 payout → statement다. */
public interface PayoutRepository {

    /** 실행할 시각이 된 READY 지급 id. 잠그지 않으며 실행권은 lockDueReady로 다시 확인한다. */
    List<Long> findDueReadyIds(LocalDateTime now, int limit);

    /** 실행할 시각이 된 READY 지급을 잠근다. 다른 실행자가 잡고 있거나 이미 바뀌었으면 비어 있다. */
    Optional<Payout> lockDueReady(Long payoutId, LocalDateTime now);

    Optional<Payout> find(Long payoutId);

    /** 결과 반영용 잠금. 다른 트랜잭션이 잡고 있으면 기다린다. */
    Optional<Payout> lock(Long payoutId);

    Optional<SettlementStatement> lockStatement(Long statementId);

    /**
     * 지급 직전 대조 게이트(settlement-design 8.2절). 한 SQL로 읽어 같은 스냅샷에서 판단한다. 통과하면 비어 있고,
     * 실패하면 처음 어긋난 검사 이름을 돌려준다.
     */
    Optional<String> gateViolation(Long payoutId, Long statementId);

    /** 이 트랜잭션의 행 잠금 대기를 제한한다. */
    void limitLockWait(long timeoutMillis);
}
