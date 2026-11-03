package com.sparta.ticket.performance.domain.schedule;

/** 좌석 준비 실패 원인. 과거 데이터의 null은 원인을 분류하지 않았음을 뜻한다. */
public enum SeatSetupFailureKind {
    RESULT_UNKNOWN,
    INVALID_REQUEST,
    INVALID_CONFIGURATION,
    SEAT_IN_USE,
    STALE_VERSION
}
