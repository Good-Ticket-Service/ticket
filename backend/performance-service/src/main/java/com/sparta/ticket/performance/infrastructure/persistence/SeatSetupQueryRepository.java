package com.sparta.ticket.performance.infrastructure.persistence;

import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.sparta.ticket.performance.application.dto.SeatSetupCommand;
import com.sparta.ticket.performance.application.port.SeatSetupReader;
import com.sparta.ticket.performance.domain.performance.QPerformance;
import com.sparta.ticket.performance.domain.performance.QPerformanceGradePrice;
import com.sparta.ticket.performance.domain.performance.QPerformanceSeatPolicy;
import com.sparta.ticket.performance.domain.venue.QVenueSeat;
import com.sparta.ticket.performance.domain.venue.QVenueSeatGrade;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 좌석 준비 요청의 좌석 행. 조인은 공개 좌석 배치(PublicSeatLayoutQueryRepository)와 같다.
 * Note: 공개 여부를 보지 않고(DRAFT 공연의 회차도 준비한다) 화면 정렬이 필요 없어서 따로 둔다.
 */
@Repository
@RequiredArgsConstructor
public class SeatSetupQueryRepository implements SeatSetupReader {

    private static final QPerformance PERFORMANCE = QPerformance.performance;
    private static final QVenueSeat SEAT = QVenueSeat.venueSeat;
    private static final QVenueSeatGrade GRADE = QVenueSeatGrade.venueSeatGrade;
    private static final QPerformanceGradePrice PRICE = QPerformanceGradePrice.performanceGradePrice;
    private static final QPerformanceSeatPolicy POLICY = QPerformanceSeatPolicy.performanceSeatPolicy;

    private final JPAQueryFactory queryFactory;

    @Override
    public List<SeatSetupCommand.Line> findSeatLines(Long performanceId) {
        return queryFactory
                .select(Projections.constructor(
                        SeatSetupCommand.Line.class,
                        SEAT.id,
                        GRADE.id,
                        GRADE.name,
                        PRICE.price,
                        POLICY.id.isNull(),
                        SEAT.section,
                        SEAT.rowLabel,
                        SEAT.seatNumber))
                .from(PERFORMANCE)
                .join(SEAT)
                .on(SEAT.venueId.eq(PERFORMANCE.venueId), SEAT.deletedAt.isNull())
                .join(GRADE)
                .on(GRADE.id.eq(SEAT.venueSeatGradeId), GRADE.venueId.eq(PERFORMANCE.venueId), GRADE.deletedAt.isNull())
                .leftJoin(PRICE)
                .on(
                        PRICE.performanceId.eq(PERFORMANCE.id),
                        PRICE.venueSeatGradeId.eq(GRADE.id),
                        PRICE.deletedAt.isNull())
                .leftJoin(POLICY)
                .on(POLICY.performanceId.eq(PERFORMANCE.id), POLICY.venueSeatId.eq(SEAT.id), POLICY.deletedAt.isNull())
                .where(PERFORMANCE.id.eq(performanceId), PERFORMANCE.deletedAt.isNull())
                .orderBy(SEAT.id.asc())
                .fetch();
    }
}
