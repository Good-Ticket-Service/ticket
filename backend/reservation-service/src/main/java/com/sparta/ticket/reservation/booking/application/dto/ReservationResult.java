package com.sparta.ticket.reservation.booking.application.dto;

import com.sparta.ticket.reservation.booking.domain.Reservation;
import com.sparta.ticket.reservation.booking.domain.ReservationStatus;
import java.time.LocalDateTime;
import java.util.List;

/** 예약 상태. 승인 인증값은 담지 않는다. orderId와 결제 금액은 결제를 준비한 뒤에만 있다. */
public record ReservationResult(
        Long reservationId,
        Long scheduleId,
        List<Long> seatIds,
        ReservationStatus status,
        LocalDateTime holdExpiresAt,
        String orderId,
        Long paymentAmount) {

    public static ReservationResult from(Reservation reservation) {
        return new ReservationResult(
                reservation.getId(),
                reservation.getScheduleId(),
                reservation.getSeatIds(),
                reservation.getStatus(),
                reservation.getHoldExpiresAt(),
                reservation.getOrderId(),
                reservation.getPaymentAmount());
    }
}
