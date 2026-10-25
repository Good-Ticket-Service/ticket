package com.sparta.ticket.reservation.application.dto;

import com.sparta.ticket.reservation.domain.Reservation;
import com.sparta.ticket.reservation.domain.ReservationStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** 예약 상태. 승인 인증값은 담지 않는다. orderId와 결제 금액은 결제를 준비한 뒤에만 있다. */
public record ReservationResult(
        UUID reservationId,
        UUID scheduleId,
        List<UUID> seatIds,
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
