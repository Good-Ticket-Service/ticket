package com.sparta.ticket.performance.infrastructure.persistence;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.CaseBuilder;
import com.querydsl.core.types.dsl.DateTimeExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.sparta.ticket.performance.application.dto.PerformancePeriod;
import com.sparta.ticket.performance.application.dto.PublicPerformanceDetail;
import com.sparta.ticket.performance.application.dto.PublicPerformancePage;
import com.sparta.ticket.performance.application.dto.PublicPerformanceSearchCondition;
import com.sparta.ticket.performance.application.dto.PublicPerformanceSort;
import com.sparta.ticket.performance.application.dto.PublicPerformanceSummary;
import com.sparta.ticket.performance.application.dto.VenueSummary;
import com.sparta.ticket.performance.application.port.PublicPerformanceReader;
import com.sparta.ticket.performance.domain.performance.OperationStatus;
import com.sparta.ticket.performance.domain.performance.QPerformance;
import com.sparta.ticket.performance.domain.performance.QPerformanceGradePrice;
import com.sparta.ticket.performance.domain.schedule.QSchedule;
import com.sparta.ticket.performance.domain.venue.QVenue;
import com.sparta.ticket.performance.domain.venue.QVenueSeatGrade;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * PublicPerformanceReader의 QueryDSL 구현. 공연·공연장·회차를 조인해 공개 응답에 필요한 값만 읽는다.
 * Note: Entity는 서로 ID로만 참조하므로 연관관계 조인 대신 {@code join(...).on(...)}으로 조인한다.
 * 조회용 인덱스는 아직 없다. 성능 기준선을 이 쿼리 그대로 측정한 뒤 인덱스·캐시를 단계별로 붙인다.
 */
@Repository
@RequiredArgsConstructor
public class PublicPerformanceQueryRepository implements PublicPerformanceReader {

    private static final QPerformance PERFORMANCE = QPerformance.performance;
    private static final QVenue VENUE = QVenue.venue;
    private static final QSchedule SCHEDULE = QSchedule.schedule;

    private final JPAQueryFactory queryFactory;

    @Override
    public PublicPerformancePage search(PublicPerformanceSearchCondition condition, LocalDateTime now) {
        BooleanBuilder where = listablePerformance().and(filters(condition));
        DateTimeExpression<LocalDateTime> firstStartAt = SCHEDULE.startAt.min();
        DateTimeExpression<LocalDateTime> lastStartAt = SCHEDULE.startAt.max();

        List<PublicPerformanceSummary> items = queryFactory
                .select(
                        PERFORMANCE.id,
                        VENUE.id,
                        VENUE.name,
                        VENUE.address,
                        VENUE.region,
                        PERFORMANCE.title,
                        PERFORMANCE.genre,
                        PERFORMANCE.posterUrl,
                        PERFORMANCE.operationStatus,
                        firstStartAt,
                        lastStartAt)
                .from(PERFORMANCE)
                .join(VENUE)
                .on(VENUE.id.eq(PERFORMANCE.venueId))
                // Note: 활성 회차가 없는 공연도 목록에 나와야 하므로(period=null) LEFT JOIN 하고 조건을 ON에 둔다.
                .leftJoin(SCHEDULE)
                .on(SCHEDULE.performanceId.eq(PERFORMANCE.id), activeSchedule(SCHEDULE))
                .where(where)
                .groupBy(
                        PERFORMANCE.id,
                        VENUE.id,
                        VENUE.name,
                        VENUE.address,
                        VENUE.region,
                        PERFORMANCE.title,
                        PERFORMANCE.genre,
                        PERFORMANCE.posterUrl,
                        PERFORMANCE.operationStatus,
                        PERFORMANCE.publishedAt)
                .orderBy(orderBy(condition.sort(), now))
                .offset((long) condition.page() * condition.size())
                .limit(condition.size())
                .fetch()
                .stream()
                .map(row -> new PublicPerformanceSummary(
                        row.get(PERFORMANCE.id),
                        new VenueSummary(
                                row.get(VENUE.id), row.get(VENUE.name), row.get(VENUE.address), row.get(VENUE.region)),
                        row.get(PERFORMANCE.title),
                        row.get(PERFORMANCE.genre),
                        row.get(PERFORMANCE.posterUrl),
                        row.get(PERFORMANCE.operationStatus),
                        PerformancePeriod.of(row.get(firstStartAt), row.get(lastStartAt))))
                .toList();

        Long total = queryFactory
                .select(PERFORMANCE.count())
                .from(PERFORMANCE)
                .join(VENUE)
                .on(VENUE.id.eq(PERFORMANCE.venueId))
                .where(where)
                .fetchOne();

        return new PublicPerformancePage(items, condition.page(), condition.size(), total == null ? 0 : total);
    }

    @Override
    public Optional<PublicPerformanceDetail> findDetail(Long performanceId) {
        Tuple row = queryFactory
                .select(
                        PERFORMANCE.id,
                        VENUE.id,
                        VENUE.name,
                        VENUE.address,
                        VENUE.region,
                        PERFORMANCE.title,
                        PERFORMANCE.genre,
                        PERFORMANCE.ageRating,
                        PERFORMANCE.runningTime,
                        PERFORMANCE.castInfo,
                        PERFORMANCE.description,
                        PERFORMANCE.posterUrl,
                        PERFORMANCE.operationStatus,
                        PERFORMANCE.publishedAt,
                        PERFORMANCE.createdAt,
                        PERFORMANCE.updatedAt)
                .from(PERFORMANCE)
                .join(VENUE)
                .on(VENUE.id.eq(PERFORMANCE.venueId))
                // 상세는 취소된 공연도 보여 준다(취소 안내). 운영 상태 조건을 걸지 않는다.
                .where(PERFORMANCE.id.eq(performanceId), PublicPerformanceCondition.publishedAndNotDeleted())
                .fetchOne();
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(new PublicPerformanceDetail(
                row.get(PERFORMANCE.id),
                new VenueSummary(row.get(VENUE.id), row.get(VENUE.name), row.get(VENUE.address), row.get(VENUE.region)),
                row.get(PERFORMANCE.title),
                row.get(PERFORMANCE.genre),
                row.get(PERFORMANCE.ageRating),
                row.get(PERFORMANCE.runningTime),
                row.get(PERFORMANCE.castInfo),
                row.get(PERFORMANCE.description),
                row.get(PERFORMANCE.posterUrl),
                row.get(PERFORMANCE.operationStatus),
                row.get(PERFORMANCE.publishedAt),
                findPeriod(performanceId),
                row.get(PERFORMANCE.createdAt),
                row.get(PERFORMANCE.updatedAt),
                findGradePrices(performanceId)));
    }

    private PerformancePeriod findPeriod(Long performanceId) {
        Tuple period = queryFactory
                .select(SCHEDULE.startAt.min(), SCHEDULE.startAt.max())
                .from(SCHEDULE)
                .where(SCHEDULE.performanceId.eq(performanceId), activeSchedule(SCHEDULE))
                .fetchOne();
        if (period == null) {
            return null;
        }
        return PerformancePeriod.of(period.get(SCHEDULE.startAt.min()), period.get(SCHEDULE.startAt.max()));
    }

    private List<PublicPerformanceDetail.GradePrice> findGradePrices(Long performanceId) {
        QPerformanceGradePrice gradePrice = QPerformanceGradePrice.performanceGradePrice;
        QVenueSeatGrade grade = QVenueSeatGrade.venueSeatGrade;
        return queryFactory
                .select(gradePrice.venueSeatGradeId, grade.name, gradePrice.price)
                .from(gradePrice)
                .join(grade)
                .on(grade.id.eq(gradePrice.venueSeatGradeId), grade.deletedAt.isNull())
                .where(gradePrice.performanceId.eq(performanceId), gradePrice.deletedAt.isNull())
                .orderBy(gradePrice.venueSeatGradeId.asc())
                .fetch()
                .stream()
                .map(price -> new PublicPerformanceDetail.GradePrice(
                        price.get(gradePrice.venueSeatGradeId), price.get(grade.name), price.get(gradePrice.price)))
                .toList();
    }

    /** 목록 대상. 명세의 "공개·미삭제·정상 공연"이므로 취소된 공연은 뺀다. */
    private BooleanBuilder listablePerformance() {
        return PublicPerformanceCondition.publishedAndNotDeleted()
                .and(PERFORMANCE.operationStatus.eq(OperationStatus.ACTIVE));
    }

    private BooleanBuilder filters(PublicPerformanceSearchCondition condition) {
        BooleanBuilder builder = new BooleanBuilder();
        if (condition.genre() != null) {
            builder.and(PERFORMANCE.genre.eq(condition.genre()));
        }
        if (condition.region() != null) {
            builder.and(VENUE.region.eq(condition.region()));
        }
        if (condition.keyword() != null) {
            builder.and(PERFORMANCE.title.containsIgnoreCase(condition.keyword()));
        }
        if (condition.from() != null || condition.to() != null) {
            builder.and(hasActiveScheduleStartingBetween(condition));
        }
        return builder;
    }

    /** 시작일(KST)이 [from, to] 안인 활성 회차가 하나라도 있는 공연. 날짜 경계는 [from 00:00, to+1일 00:00)이다. */
    private BooleanExpression hasActiveScheduleStartingBetween(PublicPerformanceSearchCondition condition) {
        QSchedule schedule = new QSchedule("dateFilterSchedule");
        BooleanBuilder range = new BooleanBuilder();
        if (condition.from() != null) {
            range.and(schedule.startAt.goe(condition.from().atStartOfDay()));
        }
        if (condition.to() != null) {
            range.and(schedule.startAt.lt(condition.to().plusDays(1).atStartOfDay()));
        }
        return JPAExpressions.selectOne()
                .from(schedule)
                .where(schedule.performanceId.eq(PERFORMANCE.id), activeSchedule(schedule), range)
                .exists();
    }

    /** 삭제·취소되지 않은 회차. 공연 기간과 날짜 필터의 기준이다. */
    private static BooleanExpression activeSchedule(QSchedule schedule) {
        return schedule.deletedAt.isNull().and(schedule.operationStatus.eq(OperationStatus.ACTIVE));
    }

    private OrderSpecifier<?>[] orderBy(PublicPerformanceSort sort, LocalDateTime now) {
        if (sort == PublicPerformanceSort.BOOKING_OPEN_ASC) {
            // 예매가 마감되지 않은 활성 회차 중 가장 이른 오픈 시각. 그런 회차가 없으면 null이라 마지막에 둔다.
            DateTimeExpression<LocalDateTime> nextBookingOpenAt = new CaseBuilder()
                    .when(SCHEDULE.bookingCloseAt.gt(now))
                    .then(SCHEDULE.bookingOpenAt)
                    .otherwise(Expressions.nullExpression(LocalDateTime.class))
                    .min();
            return new OrderSpecifier<?>[] {
                new OrderSpecifier<>(Order.ASC, nextBookingOpenAt, OrderSpecifier.NullHandling.NullsLast),
                PERFORMANCE.id.desc()
            };
        }
        return new OrderSpecifier<?>[] {PERFORMANCE.publishedAt.desc(), PERFORMANCE.id.desc()};
    }
}
