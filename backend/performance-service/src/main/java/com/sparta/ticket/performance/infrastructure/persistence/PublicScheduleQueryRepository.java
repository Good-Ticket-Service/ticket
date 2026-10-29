package com.sparta.ticket.performance.infrastructure.persistence;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.Tuple;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.sparta.ticket.performance.application.PublicScheduleReader;
import com.sparta.ticket.performance.application.dto.PublicScheduleRow;
import com.sparta.ticket.performance.domain.performance.PublicationStatus;
import com.sparta.ticket.performance.domain.performance.QPerformance;
import com.sparta.ticket.performance.domain.schedule.QSchedule;
import com.sparta.ticket.performance.domain.venue.QVenue;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * PublicScheduleReader의 QueryDSL 구현. 회차에 공연·공연장을 조인해 공개 공연의 회차만 읽는다.
 * Note: Entity는 서로 ID로만 참조하므로 {@code join(...).on(...)}으로 조인한다. 조회용 인덱스는 아직 없다(성능 기준선 측정 뒤 추가).
 */
@Repository
@RequiredArgsConstructor
public class PublicScheduleQueryRepository implements PublicScheduleReader {

    private static final QPerformance PERFORMANCE = QPerformance.performance;
    private static final QVenue VENUE = QVenue.venue;
    private static final QSchedule SCHEDULE = QSchedule.schedule;

    private final JPAQueryFactory queryFactory;

    @Override
    public boolean existsPublicPerformance(Long performanceId) {
        Integer found = queryFactory
                .selectOne()
                .from(PERFORMANCE)
                .join(VENUE)
                .on(VENUE.id.eq(PERFORMANCE.venueId))
                .where(PERFORMANCE.id.eq(performanceId), publicPerformance())
                .fetchFirst();
        return found != null;
    }

    @Override
    public List<PublicScheduleRow> findSchedules(Long performanceId) {
        return selectRows()
                .where(SCHEDULE.performanceId.eq(performanceId))
                .orderBy(SCHEDULE.startAt.asc(), SCHEDULE.id.asc())
                .fetch()
                .stream()
                .map(PublicScheduleQueryRepository::toRow)
                .toList();
    }

    @Override
    public Optional<PublicScheduleRow> findSchedule(Long scheduleId) {
        Tuple row = selectRows().where(SCHEDULE.id.eq(scheduleId)).fetchOne();
        return Optional.ofNullable(row).map(PublicScheduleQueryRepository::toRow);
    }

    /** 삭제되지 않은 회차 중 부모 공연이 공개 공연인 것. 취소 회차와 취소된 공연의 회차도 넣는다(판매 상태 CANCELED). */
    private JPAQuery<Tuple> selectRows() {
        return queryFactory
                .select(
                        SCHEDULE.id,
                        SCHEDULE.performanceId,
                        SCHEDULE.startAt,
                        SCHEDULE.bookingOpenAt,
                        SCHEDULE.bookingCloseAt,
                        SCHEDULE.operationStatus,
                        SCHEDULE.seatSetupStatus,
                        SCHEDULE.salesSuspended,
                        SCHEDULE.canceledAt,
                        PERFORMANCE.operationStatus)
                .from(SCHEDULE)
                .join(PERFORMANCE)
                .on(PERFORMANCE.id.eq(SCHEDULE.performanceId))
                .join(VENUE)
                .on(VENUE.id.eq(PERFORMANCE.venueId))
                .where(SCHEDULE.deletedAt.isNull(), publicPerformance());
    }

    private static PublicScheduleRow toRow(Tuple row) {
        return new PublicScheduleRow(
                row.get(SCHEDULE.id),
                row.get(SCHEDULE.performanceId),
                row.get(SCHEDULE.startAt),
                row.get(SCHEDULE.bookingOpenAt),
                row.get(SCHEDULE.bookingCloseAt),
                row.get(SCHEDULE.operationStatus),
                row.get(SCHEDULE.seatSetupStatus),
                Boolean.TRUE.equals(row.get(SCHEDULE.salesSuspended)),
                row.get(SCHEDULE.canceledAt),
                row.get(PERFORMANCE.operationStatus));
    }

    /**
     * 공개 공연: PUBLISHED이고 공연·공연장이 삭제되지 않았다. 공연 상세(PublicPerformanceQueryRepository)와 같은 기준이다.
     * 삭제 조건은 @SQLRestriction과 겹치지만 조회 기준을 쿼리에서 바로 읽히게 적는다.
     */
    private static BooleanBuilder publicPerformance() {
        return new BooleanBuilder()
                .and(PERFORMANCE.publicationStatus.eq(PublicationStatus.PUBLISHED))
                .and(PERFORMANCE.deletedAt.isNull())
                .and(VENUE.deletedAt.isNull());
    }
}
