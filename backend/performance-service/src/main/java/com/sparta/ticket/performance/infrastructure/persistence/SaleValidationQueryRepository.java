package com.sparta.ticket.performance.infrastructure.persistence;

import com.sparta.ticket.performance.application.dto.SaleSeat;
import com.sparta.ticket.performance.application.dto.SaleTarget;
import com.sparta.ticket.performance.application.port.SaleValidationReader;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** SaleValidationReader의 고정 JPQL 어댑터. 회차 확인과 요청 좌석 확인은 기존처럼 각각 한 번씩 읽는다(이슈 #405). */
@Repository
@RequiredArgsConstructor
public class SaleValidationQueryRepository implements SaleValidationReader {

    private final SaleValidationJpaRepository queries;

    @Override
    public Optional<SaleTarget> findTarget(Long scheduleId) {
        return queries.findTarget(scheduleId);
    }

    @Override
    public List<SaleSeat> findSeats(Long performanceId, Long venueId, Collection<Long> venueSeatIds) {
        return queries.findSeats(performanceId, venueId, venueSeatIds);
    }
}
