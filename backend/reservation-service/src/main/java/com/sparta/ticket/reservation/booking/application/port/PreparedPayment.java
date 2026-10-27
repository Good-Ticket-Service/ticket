package com.sparta.ticket.reservation.booking.application.port;

/** 결제 준비 결과. paymentWindowUrl은 결제창 주소가 필요한 PG(카카오)에만 있다. */
public record PreparedPayment(long amount, String paymentWindowUrl) {}
