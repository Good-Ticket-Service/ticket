package com.sparta.ticket.performance.infrastructure.persistence;

import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.sparta.ticket.performance.application.PublicScheduleReader;
import com.sparta.ticket.performance.application.dto.PublicScheduleRow;
import com.sparta.ticket.performance.domain.performance.OperationStatus;
import com.sparta.ticket.performance.domain.performance.QPerformance;
import com.sparta.ticket.performance.domain.schedule.QSchedule;
import com.sparta.ticket.performance.domain.venue.QVenue;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * PublicScheduleReader의 QueryDSL 구현. 공개 공연 확인은 공연·공연장 조인 한 번, 회차는 회차 테이블만 읽는다.
 * Note: 회차 목록은 부분 UNIQUE 인덱스 uq_p_schedules_start(performance_id, start_at)가 조건과 정렬을 받친다.
 * 그 밖의 조회용 인덱스는 아직 없다. 성능 기준선을 측정한 뒤 단계별로 추가한다.
 */
@Repository
@RequiredArgsConstructor
public class PublicScheduleQueryRepository implements PublicScheduleReader {

    private static final QPerformance PERFORMANCE = QPerformance.performance;
    private static final QVenue VENUE = QVenue.venue;
    private static final QSchedule SCHEDULE = QSchedule.schedule;

    private final JPAQueryFactory queryFactory;

    @Override
    public Optional<OperationStatus> findPublicPerformanceStatus(Long performanceId) {
        return Optional.ofNullable(queryFactory
                .select(PERFORMANCE.operationStatus)
                .from(PERFORMANCE)
                .join(VENUE)
                .on(VENUE.id.eq(PERFORMANCE.venueId))
                .where(PERFORMANCE.id.eq(performanceId), PublicPerformanceCondition.publishedAndNotDeleted())
                .fetchOne());
    }

    @Override
    public List<PublicScheduleRow> findSchedules(Long performanceId) {
        return selectRows()
                .where(SCHEDULE.performanceId.eq(performanceId), notDeleted())
                .orderBy(SCHEDULE.startAt.asc(), SCHEDULE.id.asc())
                .fetch();
    }

    @Override
    public Optional<PublicScheduleRow> findSchedule(Long scheduleId) {
        return Optional.ofNullable(
                selectRows().where(SCHEDULE.id.eq(scheduleId), notDeleted()).fetchOne());
    }

    private JPAQuery<PublicScheduleRow> selectRows() {
        return queryFactory
                .select(Projections.constructor(
                        PublicScheduleRow.class,
                        SCHEDULE.id,
                        SCHEDULE.performanceId,
                        SCHEDULE.startAt,
                        SCHEDULE.bookingOpenAt,
                        SCHEDULE.bookingCloseAt,
                        SCHEDULE.operationStatus,
                        SCHEDULE.seatSetupStatus,
                        SCHEDULE.salesSuspended,
                        SCHEDULE.canceledAt))
                .from(SCHEDULE);
    }

    /** 삭제 회차만 뺀다. 취소 회차는 판매 상태 CANCELED로 보여 준다. 삭제 조건은 @SQLRestriction과 겹치지만 기준을 쿼리에 적는다. */
    private static BooleanExpression notDeleted() {
        return SCHEDULE.deletedAt.isNull();
    }
}
