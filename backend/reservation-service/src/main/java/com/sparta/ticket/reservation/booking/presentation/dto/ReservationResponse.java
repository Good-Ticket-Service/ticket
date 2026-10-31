package com.sparta.ticket.reservation.booking.presentation.dto;

import com.sparta.ticket.reservation.booking.application.dto.ReservationResult;
import com.sparta.ticket.reservation.booking.application.dto.TicketResult;
import com.sparta.ticket.reservation.booking.domain.ReservationStatus;
import java.time.LocalDateTime;
import java.util.List;

public record ReservationResponse(
        Long reservationId,
        Long scheduleId,
        Long performanceId,
        String performanceTitle,
        LocalDateTime scheduleStartAt,
        List<Long> scheduleSeatIds,
        ReservationStatus status,
        LocalDateTime holdExpiresAt,
        String orderId,
        long totalPrice,
        Long userCouponId,
        Long discountAmount,
        Long paymentAmount,
        List<TicketResult> tickets) {

    public static ReservationResponse from(ReservationResult result) {
        return new ReservationResponse(
                result.reservationId(),
                result.scheduleId(),
                result.performanceId(),
                result.performanceTitle(),
                result.scheduleStartAt(),
                result.scheduleSeatIds(),
                result.status(),
                result.holdExpiresAt(),
                result.orderId(),
                result.totalPrice(),
                result.userCouponId(),
                result.discountAmount(),
                result.paymentAmount(),
                result.tickets());
    }
}
