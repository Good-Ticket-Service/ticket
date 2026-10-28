package com.sparta.ticket.reservation.booking.presentation.dto;

import com.sparta.ticket.reservation.booking.application.dto.ReservationResult;
import com.sparta.ticket.reservation.booking.domain.ReservationStatus;
import java.time.LocalDateTime;
import java.util.List;

public record ReservationResponse(
        Long reservationId,
        Long scheduleId,
        List<Long> scheduleSeatIds,
        ReservationStatus status,
        LocalDateTime holdExpiresAt,
        String orderId,
        Long paymentAmount) {

    public static ReservationResponse from(ReservationResult result) {
        return new ReservationResponse(
                result.reservationId(),
                result.scheduleId(),
                result.scheduleSeatIds(),
                result.status(),
                result.holdExpiresAt(),
                result.orderId(),
                result.paymentAmount());
    }
}
