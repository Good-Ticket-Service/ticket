package com.sparta.ticket.reservation.booking.presentation.dto;

import com.sparta.ticket.reservation.booking.domain.ReservationStatus;
import com.sparta.ticket.reservation.booking.domain.ReservationStatusView;

public record ReservationStatusResponse(Long reservationId, ReservationStatus status) {

    public static ReservationStatusResponse from(ReservationStatusView view) {
        return new ReservationStatusResponse(view.reservationId(), view.status());
    }
}
