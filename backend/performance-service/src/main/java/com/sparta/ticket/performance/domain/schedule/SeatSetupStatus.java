package com.sparta.ticket.performance.domain.schedule;

/** 회차 좌석 준비 상태. 예매 서비스가 회차 좌석을 만들면 READY가 된다. */
public enum SeatSetupStatus {
    PENDING,
    READY,
    FAILED
}
