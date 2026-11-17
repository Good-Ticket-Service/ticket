package com.sparta.ticket.performance.infrastructure.persistence;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Expression;
import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.CaseBuilder;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.core.types.dsl.StringExpression;
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
import com.sparta.ticket.performance.domain.venue.Region;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
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
    private static final char LIKE_ESCAPE = '!';

    private final JPAQueryFactory queryFactory;
    private final PublicPerformanceDetailJpaRepository detailJpaRepository;

    /**
     * 목록은 두 번에 나눠 읽는다. 먼저 페이지에 들어갈 공연 ID를 정렬 순서대로 고르고, 그 ID들의 표시 정보와 기간만 계산한다.
     * Note: 한 번에 묶으면 OFFSET으로 버릴 공연까지 모든 공연의 기간을 계산한다(이슈 #343). 여러 쿼리가 같은 시점을 보도록
     * 서비스가 REPEATABLE READ로 부른다.
     * 검색어가 없고 판매 상태가 기본(판매중·판매예정)인 목록의 예매 오픈 순은 별도 경로로 읽는다(이슈 #471).
     */
    @Override
    public PublicPerformancePage search(PublicPerformanceSearchCondition condition, LocalDateTime now) {
        BooleanBuilder base = listablePerformance().and(filters(condition));
        BooleanBuilder where = new BooleanBuilder(base.getValue()).and(saleStatus(condition, now));
        List<Long> ids = isDefaultBrowse(condition) && condition.sort() == PublicPerformanceSort.BOOKING_OPEN_ASC
                ? findDefaultBookingOpenPageIds(condition, base, now)
                : findPageIds(condition, where, now);
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

    /**
     * 검색어가 없고 판매 상태가 정확히 판매중·판매예정인 기본 목록인지. 상태 생략과 두 상태를 직접 고른 요청이 같은 경로를 탄다.
     * Note: 기본 두 상태는 "시작 전 활성 회차가 있는 공연"과 같다(saleStatus의 onSale && upcoming 분기).
     * 판매종료가 섞인 조합·단일 상태·세 상태 전체·키워드 검색은 기존 경로를 쓴다(이슈 #471 계획 5절).
     */
    private static boolean isDefaultBrowse(PublicPerformanceSearchCondition condition) {
        return condition.keyword() == null
                && condition.statuses()
                        .equals(EnumSet.of(PublicPerformanceStatus.ON_SALE, PublicPerformanceStatus.UPCOMING));
    }

    /**
     * 기본 목록의 예매 오픈 순 페이지 ID. 시작 전 활성 회차를 INNER JOIN 해 공연별 최소 오픈 시각으로 정렬한다(이슈 #471).
     * Note: 기존 경로는 정렬용 LEFT JOIN과 상태 확인용 EXISTS가 같은 회차 집합을 두 번 읽었다. 기본 두 상태에서는 INNER JOIN이
     * 남은 회차 존재를 보장하므로 EXISTS를 뺀다. 회차 수만큼 늘어난 행은 GROUP BY로 공연 하나로 묶은 뒤 페이지를 자른다.
     * 날짜 필터는 base 안의 별도 하위 쿼리라 이 JOIN의 회차와 다른 회차가 만족해도 된다(기존 의미 유지).
     * 오픈 시각이 없는 공연은 INNER JOIN에서 빠지므로 NULLS LAST가 필요 없다.
     */
    private List<Long> findDefaultBookingOpenPageIds(
            PublicPerformanceSearchCondition condition, BooleanBuilder base, LocalDateTime now) {
        return queryFactory
                .select(PERFORMANCE.id)
                .from(PERFORMANCE)
                .join(VENUE)
                .on(VENUE.id.eq(PERFORMANCE.venueId))
                .join(SCHEDULE)
                .on(SCHEDULE.performanceId.eq(PERFORMANCE.id), activeSchedule(SCHEDULE), SCHEDULE.startAt.gt(now))
                .where(base)
                .groupBy(PERFORMANCE.id)
                .orderBy(SCHEDULE.bookingOpenAt.min().asc(), PERFORMANCE.id.desc())
                .offset((long) condition.page() * condition.size())
                .limit(condition.size())
                .fetch();
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
        } else if (condition.sort() == PublicPerformanceSort.RELEVANCE) {
            // 검색어가 없으면 서비스가 400으로 막는다. 같은 등급은 LATEST와 같은 순서다.
            query.orderBy(
                    relevanceGrade(condition.keyword()).asc(), PERFORMANCE.publishedAt.desc(), PERFORMANCE.id.desc());
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
            builder.and(VENUE.region.eq(condition.region().name()));
        }
        if (condition.keyword() != null) {
            String pattern = containsPattern(condition.keyword());
            BooleanBuilder matches = new BooleanBuilder(
                            spaceless(PERFORMANCE.title).like(pattern, LIKE_ESCAPE))
                    .or(spaceless(VENUE.name).like(pattern, LIKE_ESCAPE))
                    .or(spaceless(PERFORMANCE.castInfo).like(pattern, LIKE_ESCAPE));
            Region.fromKeyword(condition.keyword()).ifPresent(region -> matches.or(VENUE.region.eq(region.name())));
            builder.and(matches);
        }
        if (condition.from() != null || condition.to() != null) {
            builder.and(hasActiveScheduleStartingBetween(condition));
        }
        return builder;
    }

    /**
     * 관련도 등급(작을수록 앞). 제목 같음 1, 제목 시작 2, 제목 포함 3, 출연진 4, 공연장명 5, 지역 이름 6이다(이슈 #442).
     * 목록 조건을 통과한 공연만 정렬하므로, 텍스트 필드에 걸리지 않은 공연은 지역 이름으로 걸린 공연이다.
     */
    private static NumberExpression<Integer> relevanceGrade(String keyword) {
        String lowered = keyword.toLowerCase(Locale.ROOT);
        String contains = containsPattern(keyword);
        StringExpression title = spaceless(PERFORMANCE.title);
        return new CaseBuilder()
                .when(title.eq(lowered))
                .then(1)
                .when(title.like(escapeLike(lowered) + "%", LIKE_ESCAPE))
                .then(2)
                .when(title.like(contains, LIKE_ESCAPE))
                .then(3)
                .when(spaceless(PERFORMANCE.castInfo).like(contains, LIKE_ESCAPE))
                .then(4)
                .when(spaceless(VENUE.name).like(contains, LIKE_ESCAPE))
                .then(5)
                .otherwise(6);
    }

    /**
     * 띄어쓰기·영문 대소문자를 무시하고 비교할 필드 식. 조회 조건과 관련도 정렬이 이 식 하나만 쓴다.
     * Note: 이슈 #441에서 색인을 만들면 이 식 모양(lower(replace(필드, ' ', '')))에 건다. 모양이 달라지면 색인을 쓰지 못한다.
     */
    private static StringExpression spaceless(StringExpression field) {
        return Expressions.stringTemplate("lower(replace({0}, ' ', ''))", field);
    }

    /** 공백을 지운 검색어의 부분 일치 패턴. %·_·!는 문자 그대로 찾는다. */
    private static String containsPattern(String keyword) {
        return "%" + escapeLike(keyword.toLowerCase(Locale.ROOT)) + "%";
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
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
