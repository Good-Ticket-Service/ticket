package com.sparta.ticket.performance.application.dto;

import com.sparta.ticket.performance.domain.performance.AgeRating;
import com.sparta.ticket.performance.domain.performance.Genre;
import com.sparta.ticket.performance.domain.performance.OperationStatus;
import java.time.LocalDateTime;
import java.util.List;

/** 공개 공연 상세. 판매자 ID·version·공개 상태 같은 관리 정보는 담지 않는다. 시각은 KST다. */
public record PublicPerformanceDetail(
        Long id,
        VenueSummary venue,
        String title,
        Genre genre,
        AgeRating ageRating,
        int runningTime,
        String castInfo,
        String description,
        String posterUrl,
        OperationStatus operationStatus,
        LocalDateTime publishedAt,
        PerformancePeriod period,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<GradePrice> gradePrices) {

    /** 공연장 고정 등급의 공연 공통 가격. */
    public record GradePrice(Long venueSeatGradeId, String gradeName, int price) {}
}
