package com.sparta.ticket.reservation.booking.application.facade;

import java.time.LocalDateTime;
import java.util.List;

/** {@link BookingFacade#getReservation}의 응답. 좌석 id는 오름차순이다(이 순서로 잠근다). */
public record ReservationView(
        Long reservationId,
        Long userId,
        Long scheduleId,
        ReservationState state,
        LocalDateTime holdExpiresAt,
        List<Long> scheduleSeatIds) {}
