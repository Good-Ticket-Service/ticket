package com.sparta.ticket.performance.application.dto;

/** 공개 공연 목록 정렬. */
public enum PublicPerformanceSort {
    /** 최근 공개 순. */
    LATEST,
    /** 마감 전 활성 회차의 가장 이른 예매 오픈 시각 순. 그런 회차가 없는 공연은 마지막에 둔다. */
    BOOKING_OPEN_ASC,
    /**
     * 검색어 관련도 순(이슈 #442). 제목 같음 → 제목 시작 → 제목 포함 → 출연진 → 공연장명 → 지역 이름 순이며, 같은 등급은 최신순이다.
     * 검색어가 있을 때만 쓸 수 있다.
     */
    RELEVANCE
}
