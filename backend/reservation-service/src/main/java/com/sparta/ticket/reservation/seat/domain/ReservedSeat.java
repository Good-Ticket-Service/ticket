package com.sparta.ticket.reservation.seat.domain;

/** 예약이 잡고 있는(HOLD·SOLD) 좌석과 그 예약. Redis에 DB 점유 좌석을 올릴 때 쓴다(#459). */
public record ReservedSeat(Long scheduleSeatId, Long reservationId) {}
