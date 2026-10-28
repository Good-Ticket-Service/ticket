package com.sparta.ticket.performance.application.performance.dto;

import com.sparta.ticket.performance.domain.performance.Genre;
import com.sparta.ticket.performance.domain.performance.OperationStatus;

/** 공개 공연 목록의 한 항목. period는 활성 회차가 없으면 null이다. */
public record PublicPerformanceSummary(
        Long id,
        VenueSummary venue,
        String title,
        Genre genre,
        String posterUrl,
        OperationStatus operationStatus,
        PerformancePeriod period) {}
