package com.sparta.ticket.reservation.booking.domain;

/** 결제 결과를 기다리는 폴링에 답할 만큼만 읽은 예약. 티켓과 금액은 읽지 않는다. userId는 본인 확인용이다. */
public record ReservationStatusView(Long reservationId, Long userId, ReservationStatus status) {}
