package com.sparta.ticket.reservation.seat.application.facade;

import java.time.LocalDateTime;

/** {@link SeatFacade#lockSeats}의 응답. 잠근 시점의 좌석 상태와 현재 점유 예약이다. AVAILABLE이면 예약과 만료 시각이 없다. */
public record SeatLock(Long scheduleSeatId, SeatState state, Long reservationId, LocalDateTime holdExpiresAt) {}
