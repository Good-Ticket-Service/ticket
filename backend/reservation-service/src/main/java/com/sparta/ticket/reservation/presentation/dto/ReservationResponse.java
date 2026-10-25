package com.sparta.ticket.reservation.presentation.dto;

import com.sparta.ticket.reservation.application.dto.ReservationResult;
import com.sparta.ticket.reservation.domain.ReservationStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record ReservationResponse(
        UUID reservationId,
        UUID scheduleId,
        List<UUID> seatIds,
        ReservationStatus status,
        LocalDateTime holdExpiresAt,
        String orderId,
        Long paymentAmount) {

    public static ReservationResponse from(ReservationResult result) {
        return new ReservationResponse(
                result.reservationId(),
                result.scheduleId(),
                result.seatIds(),
                result.status(),
                result.holdExpiresAt(),
                result.orderId(),
                result.paymentAmount());
    }
}
