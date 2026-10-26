package com.sparta.ticket.reservation.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 허용되는 상태 전이 표. 상태를 바꾸는 쿼리는 이 표의 from과 to만 쓴다.
 * 좌석에 주는 영향도 함께 둔다. 좌석을 바꾸는 전이는 좌석을 먼저 잠근 뒤 예약을 바꾼다.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum ReservationTransition {
    START_PAYMENT(ReservationStatus.HOLD, ReservationStatus.PAYMENT_IN_PROGRESS, SeatEffect.NONE),
    EXPIRE_HOLD(ReservationStatus.HOLD, ReservationStatus.EXPIRED, SeatEffect.RELEASE),
    RELEASE_HOLD(ReservationStatus.HOLD, ReservationStatus.RELEASED, SeatEffect.RELEASE),
    CONFIRM(ReservationStatus.PAYMENT_IN_PROGRESS, ReservationStatus.CONFIRMED, SeatEffect.SELL),
    RETURN_TO_HOLD(ReservationStatus.PAYMENT_IN_PROGRESS, ReservationStatus.HOLD, SeatEffect.NONE),
    EXPIRE_AFTER_FAILURE(ReservationStatus.PAYMENT_IN_PROGRESS, ReservationStatus.EXPIRED, SeatEffect.RELEASE);

    private final ReservationStatus from;
    private final ReservationStatus to;
    private final SeatEffect seatEffect;

    public enum SeatEffect {
        NONE,
        SELL,
        RELEASE
    }
}
