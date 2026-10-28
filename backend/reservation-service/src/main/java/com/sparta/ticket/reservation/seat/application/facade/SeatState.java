package com.sparta.ticket.reservation.seat.application.facade;

/** Facade로 내보내는 좌석 상태. seat domain enum을 booking에 드러내지 않으려고 따로 둔다. */
public enum SeatState {
    AVAILABLE,
    HOLD,
    SOLD
}
