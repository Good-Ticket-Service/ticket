package com.sparta.ticket.performance.infrastructure.persistence;

import com.sparta.ticket.performance.domain.performance.AgeRating;
import com.sparta.ticket.performance.domain.performance.Genre;
import com.sparta.ticket.performance.domain.performance.OperationStatus;
import java.time.LocalDateTime;

/** 공개 공연 상세 기본 정보와 활성 회차 기간 한 행. JPQL 생성자 표현식이 만들므로 public이다. */
public record PublicPerformanceDetailRow(
        Long id,
        Long venueId,
        String venueName,
        String venueAddress,
        String venueRegion,
        String title,
        Genre genre,
        AgeRating ageRating,
        String ageGuidance,
        Integer runningTime,
        String castInfo,
        String description,
        String posterUrl,
        OperationStatus operationStatus,
        LocalDateTime publishedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime firstStartAt,
        LocalDateTime lastStartAt) {}
