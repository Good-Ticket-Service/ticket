package com.sparta.ticket.performance.infrastructure.persistence;

import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.sparta.ticket.performance.application.dto.PublicSeatLayout;
import com.sparta.ticket.performance.application.dto.VenueSummary;
import com.sparta.ticket.performance.application.port.PublicSeatLayoutReader;
import com.sparta.ticket.performance.domain.performance.QPerformance;
import com.sparta.ticket.performance.domain.performance.QPerformanceGradePrice;
import com.sparta.ticket.performance.domain.performance.QPerformanceSeatPolicy;
import com.sparta.ticket.performance.domain.venue.QVenue;
import com.sparta.ticket.performance.domain.venue.QVenueSeat;
import com.sparta.ticket.performance.domain.venue.QVenueSeatGrade;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** 공개 공연·공연장 확인은 한 번만 하고 좌석에 등급·가격·제외 정책을 조합한다. */
@Repository
@RequiredArgsConstructor
public class PublicSeatLayoutQueryRepository implements PublicSeatLayoutReader {

    private static final QPerformance PERFORMANCE = QPerformance.performance;
    private static final QVenue VENUE = QVenue.venue;
    private static final QVenueSeat SEAT = QVenueSeat.venueSeat;
    private static final QVenueSeatGrade GRADE = QVenueSeatGrade.venueSeatGrade;
    private static final QPerformanceGradePrice PRICE = QPerformanceGradePrice.performanceGradePrice;
    private static final QPerformanceSeatPolicy POLICY = QPerformanceSeatPolicy.performanceSeatPolicy;
    private final JPAQueryFactory queryFactory;

    @Override
    public Optional<VenueSummary> findPublicVenue(Long performanceId) {
        return Optional.ofNullable(queryFactory
                .select(Projections.constructor(VenueSummary.class, VENUE.id, VENUE.name, VENUE.address, VENUE.region))
                .from(PERFORMANCE)
                .join(VENUE)
                .on(VENUE.id.eq(PERFORMANCE.venueId))
                .where(PERFORMANCE.id.eq(performanceId), PublicPerformanceCondition.publishedAndNotDeleted())
                .fetchOne());
    }

    @Override
    public List<PublicSeatLayout.Seat> findSeats(Long performanceId, Long venueId) {
        return queryFactory
                .select(Projections.constructor(
                        PublicSeatLayout.Seat.class,
                        SEAT.id,
                        SEAT.section,
                        SEAT.rowLabel,
                        SEAT.seatNumber,
                        GRADE.id,
                        GRADE.name,
                        POLICY.id.isNull(),
                        PRICE.price))
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
                .where(SEAT.venueId.eq(venueId), SEAT.deletedAt.isNull())
                .orderBy(
                        SEAT.section.asc(),
                        SEAT.rowLabel.length().asc(),
                        SEAT.rowLabel.asc(),
                        SEAT.seatNumber.asc(),
                        SEAT.id.asc())
                .fetch();
    }
}
