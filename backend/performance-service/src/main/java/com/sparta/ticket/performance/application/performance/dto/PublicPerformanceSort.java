package com.sparta.ticket.performance.application.performance.dto;

/** 공개 공연 목록 정렬. */
public enum PublicPerformanceSort {
    /** 최근 공개 순. */
    LATEST,
    /** 마감 전 활성 회차의 가장 이른 예매 오픈 시각 순. 그런 회차가 없는 공연은 마지막에 둔다. */
    BOOKING_OPEN_ASC
}
