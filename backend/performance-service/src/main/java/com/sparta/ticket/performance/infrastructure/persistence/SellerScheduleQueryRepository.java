package com.sparta.ticket.performance.infrastructure.persistence;

import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.sparta.ticket.performance.application.dto.SellerScheduleRow;
import com.sparta.ticket.performance.application.port.SellerScheduleReader;
import com.sparta.ticket.performance.domain.performance.QPerformance;
import com.sparta.ticket.performance.domain.schedule.QSchedule;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** SellerScheduleReader의 QueryDSL 구현. 회차와 부모 공연을 조인 한 번으로 읽는다. 잠그지 않는다. */
@Repository
@RequiredArgsConstructor
public class SellerScheduleQueryRepository implements SellerScheduleReader {

    private static final QPerformance PERFORMANCE = QPerformance.performance;
    private static final QSchedule SCHEDULE = QSchedule.schedule;

    private final JPAQueryFactory queryFactory;

    /** 삭제 조건은 @SQLRestriction과 겹치지만 기준을 쿼리에 적는다. */
    @Override
    public Optional<SellerScheduleRow> findSchedule(Long scheduleId) {
        return Optional.ofNullable(queryFactory
                .select(Projections.constructor(
                        SellerScheduleRow.class,
                        SCHEDULE.id,
                        SCHEDULE.performanceId,
                        PERFORMANCE.sellerId,
                        PERFORMANCE.operationStatus,
                        SCHEDULE.startAt,
                        SCHEDULE.bookingOpenAt,
                        SCHEDULE.bookingCloseAt,
                        SCHEDULE.operationStatus,
                        SCHEDULE.seatSetupStatus,
                        SCHEDULE.salesSuspended,
                        SCHEDULE.canceledAt,
                        SCHEDULE.seatConfigVersion,
                        SCHEDULE.seatSetupRequestId,
                        SCHEDULE.seatSetupError,
                        SCHEDULE.suspensionReason,
                        SCHEDULE.cancelReason,
                        SCHEDULE.version))
                .from(SCHEDULE)
                .join(PERFORMANCE)
                .on(PERFORMANCE.id.eq(SCHEDULE.performanceId))
                .where(SCHEDULE.id.eq(scheduleId), SCHEDULE.deletedAt.isNull(), PERFORMANCE.deletedAt.isNull())
                .fetchOne());
    }
}
