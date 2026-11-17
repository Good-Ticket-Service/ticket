package com.sparta.ticket.performance.application.dto;

import com.sparta.ticket.performance.domain.performance.Genre;
import com.sparta.ticket.performance.domain.venue.Region;
import java.time.LocalDate;
import java.util.Set;

/**
 * 공개 공연 목록 검색 조건. 선택 필터의 null은 미적용이며, 판매 상태는 정규화한 집합을 받는다.
 *
 * @param from 공연 시작일(KST) 하한, 포함
 * @param to 공연 시작일(KST) 상한, 포함
 * @param keyword 제목·공연장명·출연진 부분 일치 또는 지역 별칭 정확 일치 검색어. 스페이스를 모두 지운 값이며, 남는 것이 없으면 null이다(이슈 #442)
 */
public record PublicPerformanceSearchCondition(
        Genre genre,
        Region region,
        LocalDate from,
        LocalDate to,
        String keyword,
        Set<PublicPerformanceStatus> statuses,
        PublicPerformanceSort sort,
        int page,
        int size) {

    public PublicPerformanceSearchCondition {
        statuses = Set.copyOf(statuses);
        if (statuses.isEmpty()) {
            throw new IllegalArgumentException("판매 상태는 하나 이상이어야 합니다.");
        }
    }
}
