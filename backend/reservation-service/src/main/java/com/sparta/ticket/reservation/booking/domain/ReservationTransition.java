package com.sparta.ticket.reservation.booking.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 허용되는 상태 전이 표. 상태를 바꾸는 쿼리는 이 표의 from과 to만 쓴다.
 * 좌석을 함께 바꾸는 전이는 ReservationTransitionExecutor가 좌석을 먼저 잠근 뒤 예약을 바꾼다.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum ReservationTransition {
    START_PAYMENT(ReservationStatus.HOLD, ReservationStatus.PAYMENT_IN_PROGRESS),
    EXPIRE_HOLD(ReservationStatus.HOLD, ReservationStatus.EXPIRED),
    RELEASE_HOLD(ReservationStatus.HOLD, ReservationStatus.RELEASED),
    CONFIRM(ReservationStatus.PAYMENT_IN_PROGRESS, ReservationStatus.CONFIRMED),
    RETURN_TO_HOLD(ReservationStatus.PAYMENT_IN_PROGRESS, ReservationStatus.HOLD),
    EXPIRE_AFTER_FAILURE(ReservationStatus.PAYMENT_IN_PROGRESS, ReservationStatus.EXPIRED),
    START_CANCEL(ReservationStatus.CONFIRMED, ReservationStatus.CANCELING),
    CANCEL(ReservationStatus.CANCELING, ReservationStatus.CANCELED);

    private final ReservationStatus from;
    private final ReservationStatus to;
}
