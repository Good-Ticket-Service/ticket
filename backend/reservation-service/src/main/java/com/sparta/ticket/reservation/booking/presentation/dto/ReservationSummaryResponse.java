package com.sparta.ticket.reservation.booking.presentation.dto;

import com.sparta.ticket.reservation.booking.domain.ReservationStatus;
import com.sparta.ticket.reservation.booking.domain.ReservationSummary;
import java.time.LocalDateTime;

public record ReservationSummaryResponse(
        Long reservationId,
        Long scheduleId,
        Long performanceId,
        String performanceTitle,
        LocalDateTime scheduleStartAt,
        ReservationStatus status,
        Long paymentAmount,
        int seatCount,
        LocalDateTime createdAt) {

    public static ReservationSummaryResponse from(ReservationSummary summary) {
        return new ReservationSummaryResponse(
                summary.reservationId(),
                summary.scheduleId(),
                summary.performanceId(),
                summary.performanceTitle(),
                summary.scheduleStartAt(),
                summary.status(),
                summary.paymentAmount(),
                summary.seatCount(),
                summary.createdAt());
    }
}
