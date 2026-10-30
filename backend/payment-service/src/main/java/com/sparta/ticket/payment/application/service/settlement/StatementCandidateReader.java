package com.sparta.ticket.payment.application.service.settlement;

import com.sparta.ticket.payment.domain.repository.settlement.SettlementItemRepository;
import com.sparta.ticket.payment.domain.repository.settlement.StatementCandidate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 정산서 발행 대상 (판매자, 회차)를 키셋으로 읽는다. 잠그지 않는 조회라, 실제 편입 여부는 발행 트랜잭션이 잠근 뒤 다시 판단한다.
 * Note: Spring Batch로 바꾸면 이 클래스를 ItemReader로 감싼다.
 */
@Component
@RequiredArgsConstructor
public class StatementCandidateReader {

    /** 키셋의 처음. 어떤 회차 시작 시각보다도 앞선다. */
    static final LocalDateTime FIRST_START_AT = LocalDateTime.of(1970, 1, 1, 0, 0);

    private final SettlementItemRepository items;

    /** @param after 마지막으로 읽은 대상. 처음이면 null */
    public List<StatementCandidate> next(LocalDateTime cutoff, StatementCandidate after, int limit) {
        if (after == null) return items.findStatementCandidates(cutoff, FIRST_START_AT, 0L, 0L, limit);
        return items.findStatementCandidates(
                cutoff, after.getScheduleStartAt(), after.getScheduleId(), after.getSellerId(), limit);
    }
}
