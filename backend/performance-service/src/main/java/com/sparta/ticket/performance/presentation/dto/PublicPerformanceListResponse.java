package com.sparta.ticket.performance.presentation.dto;

import com.sparta.ticket.performance.application.performance.dto.PerformancePeriod;
import com.sparta.ticket.performance.application.performance.dto.PublicPerformancePage;
import com.sparta.ticket.performance.application.performance.dto.PublicPerformanceSummary;
import com.sparta.ticket.performance.application.performance.dto.VenueSummary;
import java.util.List;

/**
 * 공개 공연 목록 응답의 data. 공연 명세의 목록 형식(items, page, size, totalElements)이며 공통 PageResponse와 다르다.
 * version·publicationStatus·sellerId는 넣지 않는다.
 */
public record PublicPerformanceListResponse(List<Item> items, int page, int size, long totalElements) {

    public static PublicPerformanceListResponse from(PublicPerformancePage page) {
        return new PublicPerformanceListResponse(
                page.items().stream().map(Item::from).toList(), page.page(), page.size(), page.totalElements());
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
