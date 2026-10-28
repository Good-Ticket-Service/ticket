package com.sparta.ticket.performance.presentation.dto;

import com.sparta.ticket.performance.application.performance.dto.PerformancePeriod;
import com.sparta.ticket.performance.application.performance.dto.PublicPerformanceDetail;
import com.sparta.ticket.performance.application.performance.dto.VenueSummary;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 공개 공연 상세 응답의 data. 시각은 +09:00 오프셋을 붙인 ISO 8601로 내보낸다.
 * version·publicationStatus·sellerId와 판매 제외 사유는 넣지 않는다.
 */
public record PublicPerformanceDetailResponse(
        Long id,
        VenueSummary venue,
        String title,
        String genre,
        String ageRating,
        int runningTime,
        String castInfo,
        String description,
        String posterUrl,
        String operationStatus,
        OffsetDateTime publishedAt,
        PerformancePeriod period,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<PublicPerformanceDetail.GradePrice> gradePrices) {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    public static PublicPerformanceDetailResponse from(PublicPerformanceDetail detail) {
        return new PublicPerformanceDetailResponse(
                detail.id(),
                detail.venue(),
                detail.title(),
                detail.genre().name(),
                detail.ageRating().code(),
                detail.runningTime(),
                detail.castInfo(),
                detail.description(),
                detail.posterUrl(),
                detail.operationStatus().name(),
                offset(detail.publishedAt()),
                detail.period(),
                offset(detail.createdAt()),
                offset(detail.updatedAt()),
                detail.gradePrices());
    }

    /** DB의 KST 시각에 +09:00 오프셋을 붙인다. */
    private static OffsetDateTime offset(LocalDateTime time) {
        return time == null ? null : time.atZone(SEOUL).toOffsetDateTime();
    }
}
