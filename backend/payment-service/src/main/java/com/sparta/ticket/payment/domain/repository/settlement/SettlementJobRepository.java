package com.sparta.ticket.payment.domain.repository.settlement;

import com.sparta.ticket.payment.domain.model.settlement.SettlementJob;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** domain이 필요로 하는 저장소 기능만 선언한다. 구현은 infrastructure의 Spring Data 인터페이스가 맡는다. */
public interface SettlementJobRepository {

    /** 실행 시각이 된 대기 작업 하나를 잠근다(SKIP LOCKED). 이번 실행에서 이미 시도한 작업은 뺀다. */
    List<SettlementJob> lockNextDue(LocalDateTime now, Collection<Long> excludedIds);

    Optional<SettlementJob> lockById(Long id);

    SettlementJob saveAndFlush(SettlementJob job);
}
