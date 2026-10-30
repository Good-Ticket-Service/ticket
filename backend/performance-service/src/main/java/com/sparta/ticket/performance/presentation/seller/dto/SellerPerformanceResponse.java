package com.sparta.ticket.performance.presentation.seller.dto;

import com.sparta.ticket.performance.application.dto.PerformancePeriod;
import com.sparta.ticket.performance.application.dto.SellerPerformanceDetail;
import com.sparta.ticket.performance.application.dto.VenueSummary;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** 판매자용 공연 응답(P8). 시각은 +09:00 오프셋을 붙인 ISO 8601로 내보낸다. */
public record SellerPerformanceResponse(
        Long id,
        VenueSummary venue,
        String title,
        String genre,
        String ageRating,
        int runningTime,
        String castInfo,
        String description,
        String posterUrl,
        String publicationStatus,
        String operationStatus,
        OffsetDateTime publishedAt,
        PerformancePeriod period,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        Long sellerId) {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    public static SellerPerformanceResponse from(SellerPerformanceDetail detail) {
        return new SellerPerformanceResponse(
                detail.id(),
                detail.venue(),
                detail.title(),
                detail.genre().name(),
                detail.ageRating().code(),
                detail.runningTime(),
                detail.castInfo(),
                detail.description(),
                detail.posterUrl(),
                detail.publicationStatus().name(),
                detail.operationStatus().name(),
                offset(detail.publishedAt()),
                detail.period(),
                detail.version(),
                offset(detail.createdAt()),
                offset(detail.updatedAt()),
                detail.sellerId());
    }

    private static OffsetDateTime offset(LocalDateTime time) {
        return time == null ? null : time.atZone(SEOUL).toOffsetDateTime();
    }
}
