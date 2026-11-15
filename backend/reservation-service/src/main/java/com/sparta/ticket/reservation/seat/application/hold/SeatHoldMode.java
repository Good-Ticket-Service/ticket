package com.sparta.ticket.reservation.seat.application.hold;

/** 선점 저장 위치(#449, reservation.seat.hold.mode). */
public enum SeatHoldMode {
    /** 선점 때 예약·티켓을 DB에 만든다(지금 방식). */
    DB,
    /** 선점은 Redis에만 두고, 결제하기 때 DB 예약으로 바꾼다. */
    REDIS
}
