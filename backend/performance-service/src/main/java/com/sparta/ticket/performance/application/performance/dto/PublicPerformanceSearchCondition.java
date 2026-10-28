package com.sparta.ticket.performance.application.performance.dto;

import com.sparta.ticket.performance.domain.performance.Genre;
import java.time.LocalDate;

/**
 * 공개 공연 목록 검색 조건. null인 필터는 적용하지 않는다.
 *
 * @param from 공연 시작일(KST) 하한, 포함
 * @param to 공연 시작일(KST) 상한, 포함
 * @param keyword 공연명 검색어. 앞뒤 공백을 지운 값이며 빈 문자열은 null로 바꿔 넘긴다
 */
public record PublicPerformanceSearchCondition(
        Genre genre,
        String region,
        LocalDate from,
        LocalDate to,
        String keyword,
        PublicPerformanceSort sort,
        int page,
        int size) {}
