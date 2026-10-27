package com.sparta.ticket.reservation.booking.application.facade;

/** Facade로 알려 주는 예약 상태. booking의 domain enum을 밖에 내보이지 않으려고 따로 둔다. */
public enum ReservationState {
    HOLD,
    PAYMENT_IN_PROGRESS,
    CONFIRMED,
    EXPIRED,
    RELEASED,
    CANCELING,
    CANCELED
}
