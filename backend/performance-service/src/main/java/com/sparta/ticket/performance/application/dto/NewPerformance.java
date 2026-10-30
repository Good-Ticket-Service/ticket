package com.sparta.ticket.performance.application.dto;

import com.sparta.ticket.performance.domain.performance.AgeRating;
import com.sparta.ticket.performance.domain.performance.Genre;

/** 공연 초안 등록 입력. 공백 정리·형식 검증은 요청 DTO에서 끝난 값이다. 선택 항목은 비었으면 null이다. */
public record NewPerformance(
        Long venueId,
        String title,
        Genre genre,
        AgeRating ageRating,
        int runningTime,
        String castInfo,
        String description,
        String posterUrl) {}
