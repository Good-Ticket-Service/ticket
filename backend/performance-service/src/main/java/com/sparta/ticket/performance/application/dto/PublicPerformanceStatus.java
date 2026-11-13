package com.sparta.ticket.performance.application.dto;

/** 공개 공연 목록의 판매 상태 필터. 개별 회차의 실제 예매 가능 여부와 구분한다. */
public enum PublicPerformanceStatus {
    ON_SALE,
    UPCOMING,
    ENDED
}
