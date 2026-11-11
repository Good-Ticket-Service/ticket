package com.sparta.ticket.performance.presentation.dto;

import com.sparta.ticket.common.response.PageResponse;
import com.sparta.ticket.performance.application.dto.PerformancePeriod;
import com.sparta.ticket.performance.application.dto.PublicPerformancePage;
import com.sparta.ticket.performance.application.dto.PublicPerformanceSort;
import com.sparta.ticket.performance.application.dto.PublicPerformanceSummary;
import com.sparta.ticket.performance.application.dto.VenueSummary;

/**
 * 공개 공연 목록 응답의 data를 만든다. 공통 {@link PageResponse}의 필드 구조를 쓴다(이슈 #364).
 * sort에는 공연 목록의 기존 정렬 이름(LATEST·BOOKING_OPEN_ASC)을 그대로 넣는다. 다른 목록의 "필드,방향" 형식과는 다르다.
 * version·publicationStatus·sellerId는 넣지 않는다.
 */
public final class PublicPerformanceListResponse {

    private PublicPerformanceListResponse() {}

    /** 직접 만든 페이지라 Spring Data Page 대신 생성자로 만든다. totalPages는 Page와 같은 규칙(전체 0건이면 0)이다. */
    public static PageResponse<Item> from(PublicPerformancePage page, PublicPerformanceSort sort) {
        int totalPages = (int) ((page.totalElements() + page.size() - 1) / page.size());
        return new PageResponse<>(
                page.items().stream().map(Item::from).toList(),
                page.page(),
                page.size(),
                page.totalElements(),
                totalPages,
                sort.name());
    }

    /** period는 활성 회차가 없으면 null이다. */
    public record Item(
            Long id,
            VenueSummary venue,
            String title,
            String genre,
            String posterUrl,
            String operationStatus,
            PerformancePeriod period) {

        static Item from(PublicPerformanceSummary summary) {
            return new Item(
                    summary.id(),
                    summary.venue(),
                    summary.title(),
                    summary.genre().name(),
                    summary.posterUrl(),
                    summary.operationStatus().name(),
                    summary.period());
        }
    }
}
