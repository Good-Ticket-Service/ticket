package com.sparta.ticket.reservation.booking.domain;

import java.time.LocalDateTime;

/** 내 예매 목록의 한 줄. 티켓을 읽지 않고 좌석 수만 세어, 목록 한 쪽을 읽는 데 쿼리가 예약 수만큼 늘지 않게 한다. */
public record ReservationSummary(
        Long reservationId,
        Long scheduleId,
        Long performanceId,
        String performanceTitle,
        LocalDateTime scheduleStartAt,
        ReservationStatus status,
        Long paymentAmount,
        int seatCount,
        LocalDateTime createdAt) {}
