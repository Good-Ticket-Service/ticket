package com.sparta.ticket.performance.infrastructure.persistence;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Expression;
import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.sparta.ticket.performance.application.dto.PerformancePeriod;
import com.sparta.ticket.performance.application.dto.PublicPerformanceDetail;
import com.sparta.ticket.performance.application.dto.PublicPerformancePage;
import com.sparta.ticket.performance.application.dto.PublicPerformanceSearchCondition;
import com.sparta.ticket.performance.application.dto.PublicPerformanceSort;
import com.sparta.ticket.performance.application.dto.PublicPerformanceStatus;
import com.sparta.ticket.performance.application.dto.PublicPerformanceSummary;
import com.sparta.ticket.performance.application.dto.VenueSummary;
import com.sparta.ticket.performance.application.port.PublicPerformanceReader;
import com.sparta.ticket.performance.domain.performance.OperationStatus;
import com.sparta.ticket.performance.domain.performance.PublicationStatus;
import com.sparta.ticket.performance.domain.performance.QPerformance;
import com.sparta.ticket.performance.domain.schedule.QSchedule;
import com.sparta.ticket.performance.domain.venue.QVenue;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * PublicPerformanceReader의 QueryDSL 구현. 공연·공연장·회차를 조인해 공개 응답에 필요한 값만 읽는다.
 * Note: Entity는 서로 ID로만 참조하므로 연관관계 조인 대신 {@code join(...).on(...)}으로 조인한다.
 * 목록은 동적 조건이므로 QueryDSL을 유지한다. 판매 상태는 요청 시각과 활성 회차로 판단한다(이슈 #382).
 */
@Repository
@RequiredArgsConstructor
public class PublicPerformanceQueryRepository implements PublicPerformanceReader {

    private static final QPerformance PERFORMANCE = QPerformance.performance;
    private static final QVenue VENUE = QVenue.venue;
    private static final QSchedule SCHEDULE = QSchedule.schedule;

    private final JPAQueryFactory queryFactory;
    private final PublicPerformanceDetailJpaRepository detailJpaRepository;

    /**
     * 목록은 두 번에 나눠 읽는다. 먼저 페이지에 들어갈 공연 ID를 정렬 순서대로 고르고, 그 ID들의 표시 정보와 기간만 계산한다.
     * Note: 한 번에 묶으면 OFFSET으로 버릴 공연까지 모든 공연의 기간을 계산한다(이슈 #343). 여러 쿼리가 같은 시점을 보도록
     * 서비스가 REPEATABLE READ로 부른다.
     */
    @Override
    public PublicPerformancePage search(PublicPerformanceSearchCondition condition, LocalDateTime now) {
        BooleanBuilder where = listablePerformance().and(filters(condition)).and(saleStatus(condition, now));
        List<Long> ids = findPageIds(condition, where, now);
        List<PublicPerformanceSummary> items = ids.isEmpty() ? List.of() : findSummaries(ids);

        Long total = queryFactory
                .select(PERFORMANCE.count())
                .from(PERFORMANCE)
                .join(VENUE)
                .on(VENUE.id.eq(PERFORMANCE.venueId))
                .where(where)
                .fetchOne();

        return new PublicPerformancePage(items, condition.page(), condition.size(), total == null ? 0 : total);
    }

    /** 페이지에 들어갈 공연 ID를 정렬 순서대로 고른다. 기간은 계산하지 않는다. */
    private List<Long> findPageIds(
            PublicPerformanceSearchCondition condition, BooleanBuilder where, LocalDateTime now) {
        JPAQuery<Long> query = queryFactory
                .select(PERFORMANCE.id)
                .from(PERFORMANCE)
                .join(VENUE)
                .on(VENUE.id.eq(PERFORMANCE.venueId));
        if (condition.sort() == PublicPerformanceSort.BOOKING_OPEN_ASC) {
            // 시작 전인 활성 회차만 붙여 가장 이른 오픈 시각을 구한다. 그런 회차가 없는 공연도 남도록
            // LEFT JOIN 하고 조건을 ON에 둔다. 이때 오픈 시각은 null이라 마지막에 놓인다.
            query.leftJoin(SCHEDULE)
                    .on(SCHEDULE.performanceId.eq(PERFORMANCE.id), activeSchedule(SCHEDULE), SCHEDULE.startAt.gt(now))
                    .groupBy(PERFORMANCE.id)
                    .orderBy(
                            new OrderSpecifier<>(
                                    Order.ASC, SCHEDULE.bookingOpenAt.min(), OrderSpecifier.NullHandling.NullsLast),
                            PERFORMANCE.id.desc());
        } else {
            query.orderBy(PERFORMANCE.publishedAt.desc(), PERFORMANCE.id.desc());
        }
        return query.where(where)
                .offset((long) condition.page() * condition.size())
                .limit(condition.size())
                .fetch();
    }

    /**
     * 고른 공연의 표시 정보와 기간(전체 활성 회차 기준). IN 결과는 순서가 없으므로 ids 순서로 다시 맞춘다.
     * Note: 기간은 공연마다 첫·마지막 시작 시각만 하위 쿼리로 읽는다. (performance_id, start_at) 인덱스를 양 끝에서부터 읽다가
     * 첫 활성 회차에서 멈추므로, 회차를 모두 붙여 묶을 때처럼 판매 중 공연의 회차 수백 개를 읽지 않는다(이슈 #343 변경 후 측정).
     * 인덱스 조건에 운영 상태가 없어, 끝쪽에 취소 회차가 몰려 있으면 그만큼 더 읽는다. 활성 회차가 없으면 null이다.
     * 대상이 이미 정한 ID들이라 하위 쿼리도 그 수만큼만 실행된다.
     */
    private List<PublicPerformanceSummary> findSummaries(List<Long> ids) {
        Expression<LocalDateTime> firstStartAt = startAtOfActiveSchedules(new QSchedule("firstSchedule"), true);
        Expression<LocalDateTime> lastStartAt = startAtOfActiveSchedules(new QSchedule("lastSchedule"), false);

        Map<Long, PublicPerformanceSummary> byId = queryFactory
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
                .where(PERFORMANCE.id.in(ids))
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
                .collect(Collectors.toMap(PublicPerformanceSummary::id, Function.identity()));
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    /** 상세는 조건이 고정이라 JPQL 저장소로 읽는다(이슈 #370). 기간은 기본 정보와 함께, 가격은 따로 읽는다. */
    @Override
    public Optional<PublicPerformanceDetail> findDetail(Long performanceId) {
        return detailJpaRepository
                .findDetail(performanceId, PublicationStatus.PUBLISHED, OperationStatus.ACTIVE)
                .map(row -> new PublicPerformanceDetail(
                        row.id(),
                        new VenueSummary(row.venueId(), row.venueName(), row.venueAddress(), row.venueRegion()),
                        row.title(),
                        row.genre(),
                        row.ageRating(),
                        row.ageGuidance(),
                        row.runningTime(),
                        row.castInfo(),
                        row.description(),
                        row.posterUrl(),
                        row.operationStatus(),
                        row.publishedAt(),
                        PerformancePeriod.of(row.firstStartAt(), row.lastStartAt()),
                        row.createdAt(),
                        row.updatedAt(),
                        detailJpaRepository.findGradePrices(performanceId).stream()
                                .map(price -> new PublicPerformanceDetail.GradePrice(
                                        price.venueSeatGradeId(), price.gradeName(), price.price()))
                                .toList()));
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

    /** 상태·날짜는 독립된 공연 단위 조건이며, 목록과 count에 동일한 요청 시각으로 적용한다. */
    private BooleanBuilder saleStatus(PublicPerformanceSearchCondition condition, LocalDateTime now) {
        if (condition.statuses().containsAll(EnumSet.allOf(PublicPerformanceStatus.class))) {
            return new BooleanBuilder();
        }
        QSchedule remaining = new QSchedule("remainingSchedule");
        BooleanExpression hasRemaining = JPAExpressions.selectOne()
                .from(remaining)
                .where(remaining.performanceId.eq(PERFORMANCE.id), activeSchedule(remaining), remaining.startAt.gt(now))
                .exists();
        // Note: 이미 시작한 활성 회차의 오픈도 포함해야 시간 경과만으로 판매중이 판매예정으로 되돌아가지 않는다.
        QSchedule opened = new QSchedule("openedSchedule");
        BooleanExpression hasOpened = JPAExpressions.selectOne()
                .from(opened)
                .where(opened.performanceId.eq(PERFORMANCE.id), activeSchedule(opened), opened.bookingOpenAt.loe(now))
                .exists();
        boolean onSale = condition.statuses().contains(PublicPerformanceStatus.ON_SALE);
        boolean upcoming = condition.statuses().contains(PublicPerformanceStatus.UPCOMING);
        BooleanBuilder selected = new BooleanBuilder();
        if (onSale && upcoming) {
            selected.or(hasRemaining);
        } else if (onSale) {
            selected.or(hasRemaining.and(hasOpened));
        } else if (upcoming) {
            selected.or(hasRemaining.and(hasOpened.not()));
        }
        if (condition.statuses().contains(PublicPerformanceStatus.ENDED)) {
            selected.or(hasRemaining.not());
        }
        return selected;
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

    /** 공연의 활성 회차 중 첫(first=true) 또는 마지막 시작 시각. 활성 회차가 없으면 null이다. */
    private static Expression<LocalDateTime> startAtOfActiveSchedules(QSchedule schedule, boolean first) {
        return JPAExpressions.select(first ? schedule.startAt.min() : schedule.startAt.max())
                .from(schedule)
                .where(schedule.performanceId.eq(PERFORMANCE.id), activeSchedule(schedule));
    }

    /** 삭제·취소되지 않은 회차. 공연 기간과 날짜 필터의 기준이다. */
    private static BooleanExpression activeSchedule(QSchedule schedule) {
        return schedule.deletedAt.isNull().and(schedule.operationStatus.eq(OperationStatus.ACTIVE));
    }
}
