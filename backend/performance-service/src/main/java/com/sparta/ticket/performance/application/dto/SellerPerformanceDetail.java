package com.sparta.ticket.performance.application.dto;

import com.sparta.ticket.performance.domain.performance.AgeRating;
import com.sparta.ticket.performance.domain.performance.Genre;
import com.sparta.ticket.performance.domain.performance.OperationStatus;
import com.sparta.ticket.performance.domain.performance.Performance;
import com.sparta.ticket.performance.domain.performance.PublicationStatus;
import com.sparta.ticket.performance.domain.venue.Venue;
import java.time.LocalDateTime;

/** 판매자용 공연 결과(P8). 공개 응답과 달리 version·publicationStatus·sellerId를 가진다. 시각은 KST LocalDateTime이다. */
public record SellerPerformanceDetail(
        Long id,
        VenueSummary venue,
        String title,
        Genre genre,
        AgeRating ageRating,
        int runningTime,
        String castInfo,
        String description,
        String posterUrl,
        PublicationStatus publicationStatus,
        OperationStatus operationStatus,
        LocalDateTime publishedAt,
        PerformancePeriod period,
        long version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Long sellerId) {

    public static SellerPerformanceDetail of(Performance performance, Venue venue, PerformancePeriod period) {
        return new SellerPerformanceDetail(
                performance.getId(),
                new VenueSummary(venue.getId(), venue.getName(), venue.getAddress(), venue.getRegion()),
                performance.getTitle(),
                performance.getGenre(),
                performance.getAgeRating(),
                performance.getRunningTime(),
                performance.getCastInfo(),
                performance.getDescription(),
                performance.getPosterUrl(),
                performance.getPublicationStatus(),
                performance.getOperationStatus(),
                performance.getPublishedAt(),
                period,
                performance.getVersion(),
                performance.getCreatedAt(),
                performance.getUpdatedAt(),
                performance.getSellerId());
    }
}
