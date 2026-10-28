package com.sparta.ticket.reservation.seat.domain;

/** 회차 좌석의 판매 상태. 좌석 준비 때 AVAILABLE로 만든다(Notion 테이블 명세서 schedule_seat 절). */
public enum SeatStatus {
    AVAILABLE,
    HOLD,
    SOLD
}
