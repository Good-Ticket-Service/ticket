package com.sparta.ticket.performance.infrastructure.persistence;

import com.querydsl.core.BooleanBuilder;
import com.sparta.ticket.performance.domain.performance.PublicationStatus;
import com.sparta.ticket.performance.domain.performance.QPerformance;
import com.sparta.ticket.performance.domain.venue.QVenue;

/** 공개 조회의 공통 조건. 공연 목록·상세와 회차 목록·상세가 같은 "공개 공연" 기준을 쓰도록 한곳에 둔다. */
final class PublicPerformanceCondition {

    private static final QPerformance PERFORMANCE = QPerformance.performance;
    private static final QVenue VENUE = QVenue.venue;

    private PublicPerformanceCondition() {}

    /**
     * 공개됐고 공연·공연장이 삭제되지 않은 공연. 취소된 공연도 포함한다. 쿼리에 공연장 조인이 있어야 한다.
     * 삭제 조건은 @SQLRestriction과 겹치지만 조회 기준을 쿼리에서 바로 읽히게 적는다.
     */
    static BooleanBuilder publishedAndNotDeleted() {
        return new BooleanBuilder()
                .and(PERFORMANCE.publicationStatus.eq(PublicationStatus.PUBLISHED))
                .and(PERFORMANCE.deletedAt.isNull())
                .and(VENUE.deletedAt.isNull());
    }
}
