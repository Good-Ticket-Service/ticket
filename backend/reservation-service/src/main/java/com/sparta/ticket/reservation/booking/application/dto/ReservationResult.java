package com.sparta.ticket.reservation.booking.application.dto;

import com.sparta.ticket.reservation.booking.domain.Reservation;
import com.sparta.ticket.reservation.booking.domain.ReservationStatus;
import java.time.LocalDateTime;
import java.util.List;

/** 예약 상태. 승인 인증값은 담지 않는다. orderId와 결제 금액은 결제를 준비한 뒤에만 있다. */
public record ReservationResult(
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

    public static ReservationResult from(Reservation reservation) {
        return new ReservationResult(
                reservation.getId(),
                reservation.getScheduleId(),
                reservation.getPerformanceId(),
                reservation.getPerformanceTitle(),
                reservation.getScheduleStartAt(),
                reservation.getSeatIds(),
                reservation.getStatus(),
                reservation.getHoldExpiresAt(),
                reservation.getOrderId(),
                reservation.baseAmount(),
                reservation.getUserCouponId(),
                reservation.getDiscountAmount(),
                reservation.getPaymentAmount(),
                tickets(reservation));
    }

    /** 취소한 예약의 코드는 입장에 쓸 수 없으므로 확정 상태에서만 보여 준다. 코드 자체는 기록으로 남긴다. */
    private static List<TicketResult> tickets(Reservation reservation) {
        boolean showCode = reservation.getStatus() == ReservationStatus.CONFIRMED;
        return reservation.ticketsBySeat().stream()
                .map(ticket -> new TicketResult(
                        ticket.getScheduleSeatId(), ticket.getPrice(), showCode ? ticket.getTicketCode() : null))
                .toList();
    }
}
