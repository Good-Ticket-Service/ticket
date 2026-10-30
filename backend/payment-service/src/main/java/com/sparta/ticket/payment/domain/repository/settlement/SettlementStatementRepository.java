package com.sparta.ticket.payment.domain.repository.settlement;

import com.sparta.ticket.payment.domain.model.settlement.Payout;
import com.sparta.ticket.payment.domain.model.settlement.SettlementStatement;
import com.sparta.ticket.payment.domain.model.settlement.SettlementStatementLine;
import java.util.List;
import java.util.Optional;

/** 정산서 발행이 쓰는 저장소. 정산서·명세·지급은 추가만 하고 발행 뒤 금액을 바꾸지 않는다. */
public interface SettlementStatementRepository {

    SettlementStatement saveAndFlush(SettlementStatement statement);

    /** 판매자 기준 행을 잠근 상태에서 부른다. 같은 판매자·회차의 다음 순번(없으면 0). */
    int nextSequence(Long sellerId, Long scheduleId);

    void saveLines(List<SettlementStatementLine> lines);

    Payout savePayout(Payout payout);

    /** 이 트랜잭션의 행 잠금 대기를 제한한다. 넘으면 예외로 끝나고 잡이 다음 실행에서 다시 찾는다. */
    void limitLockWait(long timeoutMillis);

    /** 판매자 기준 행이 없으면 만든다. 다른 트랜잭션이 같은 판매자 행을 만드는 중이면 그 커밋을 기다릴 수 있다. */
    void ensureSellerAccount(Long sellerId);

    /** 판매자 기준 행을 잠근다. 다른 발행이 잡고 있으면 기다리지 않고 비어 있다. */
    Optional<Long> lockSellerAccountSkipLocked(Long sellerId);
}
