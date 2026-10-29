package com.sparta.ticket.reservation.booking.domain;

/** 예약 상태. 허용되는 전이는 {@link ReservationTransition}에만 있다. 이름은 ERD 명세서와 대조 전이다. */
public enum ReservationStatus {
    HOLD,
    PAYMENT_IN_PROGRESS,
    CONFIRMED,
    CANCELING,
    EXPIRED,
    RELEASED,
    CANCELED
}
