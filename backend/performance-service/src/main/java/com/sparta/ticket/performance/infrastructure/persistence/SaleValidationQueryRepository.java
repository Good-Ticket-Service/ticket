package com.sparta.ticket.performance.infrastructure.persistence;

import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.sparta.ticket.performance.application.dto.SaleSeat;
import com.sparta.ticket.performance.application.dto.SaleTarget;
import com.sparta.ticket.performance.application.port.SaleValidationReader;
import com.sparta.ticket.performance.domain.performance.QPerformance;
import com.sparta.ticket.performance.domain.performance.QPerformanceGradePrice;
import com.sparta.ticket.performance.domain.performance.QPerformanceSeatPolicy;
import com.sparta.ticket.performance.domain.schedule.QSchedule;
import com.sparta.ticket.performance.domain.venue.QVenue;
import com.sparta.ticket.performance.domain.venue.QVenueSeat;
import com.sparta.ticket.performance.domain.venue.QVenueSeatGrade;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * SaleValidationReader의 QueryDSL 구현. 회차·공연·공연장을 한 번 조인해 읽고, 요청 좌석은 ID 목록으로 한 번에 읽는다.
 * Note: 공개 조회와 달리 초안·취소 공연도 읽는다. 공개 여부는 거절 사유(PERFORMANCE_NOT_PUBLISHED)로 판단하기 때문이다.
 * 가격·판매 제외 LEFT JOIN은 부분 UNIQUE(uq_p_performance_grade_prices_grade, uq_p_performance_seat_policies_seat)로 좌석당 한 행이다.
 */
@Repository
@RequiredArgsConstructor
public class SaleValidationQueryRepository implements SaleValidationReader {

    private static final QSchedule SCHEDULE = QSchedule.schedule;
    private static final QPerformance PERFORMANCE = QPerformance.performance;
    private static final QVenue VENUE = QVenue.venue;
    private static final QVenueSeat SEAT = QVenueSeat.venueSeat;
    private static final QVenueSeatGrade GRADE = QVenueSeatGrade.venueSeatGrade;
    private static final QPerformanceGradePrice PRICE = QPerformanceGradePrice.performanceGradePrice;
    private static final QPerformanceSeatPolicy POLICY = QPerformanceSeatPolicy.performanceSeatPolicy;

    private final JPAQueryFactory queryFactory;

    @Override
    public Optional<SaleTarget> findTarget(Long scheduleId) {
        return Optional.ofNullable(queryFactory
                .select(Projections.constructor(
                        SaleTarget.class,
                        SCHEDULE.id,
                        PERFORMANCE.id,
                        PERFORMANCE.sellerId,
                        PERFORMANCE.venueId,
                        PERFORMANCE.title,
                        SCHEDULE.startAt,
                        SCHEDULE.bookingOpenAt,
                        SCHEDULE.bookingCloseAt,
                        PERFORMANCE.publicationStatus,
                        PERFORMANCE.operationStatus,
                        PERFORMANCE.version,
                        SCHEDULE.operationStatus,
                        SCHEDULE.salesSuspended,
                        SCHEDULE.seatSetupStatus,
                        SCHEDULE.seatConfigVersion))
                .from(SCHEDULE)
                .join(PERFORMANCE)
                .on(PERFORMANCE.id.eq(SCHEDULE.performanceId))
                .join(VENUE)
                .on(VENUE.id.eq(PERFORMANCE.venueId))
                // 삭제 조건은 @SQLRestriction과 겹치지만 404 기준을 쿼리에서 바로 읽히게 적는다
                .where(
                        SCHEDULE.id.eq(scheduleId),
                        SCHEDULE.deletedAt.isNull(),
                        PERFORMANCE.deletedAt.isNull(),
                        VENUE.deletedAt.isNull())
                .fetchOne());
    }

    @Override
    public List<SaleSeat> findSeats(Long performanceId, Long venueId, Collection<Long> venueSeatIds) {
        return queryFactory
                .select(Projections.constructor(
                        SaleSeat.class, SEAT.id, GRADE.id, GRADE.name, POLICY.id.isNull(), PRICE.price))
                .from(SEAT)
                .join(GRADE)
                .on(GRADE.id.eq(SEAT.venueSeatGradeId), GRADE.venueId.eq(venueId), GRADE.deletedAt.isNull())
                .leftJoin(PRICE)
                .on(
                        PRICE.performanceId.eq(performanceId),
                        PRICE.venueSeatGradeId.eq(GRADE.id),
                        PRICE.deletedAt.isNull())
                .leftJoin(POLICY)
                .on(POLICY.performanceId.eq(performanceId), POLICY.venueSeatId.eq(SEAT.id), POLICY.deletedAt.isNull())
                .where(SEAT.id.in(venueSeatIds), SEAT.venueId.eq(venueId), SEAT.deletedAt.isNull())
                .fetch();
    }
}
