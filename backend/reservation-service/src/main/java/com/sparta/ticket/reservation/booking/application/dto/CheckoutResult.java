package com.sparta.ticket.reservation.booking.application.dto;

/** 결제 준비 결과. 클라이언트는 orderId로 결제창을 띄우고, 인증 뒤 같은 orderId로 승인을 요청한다. */
public record CheckoutResult(String orderId, long amount, String paymentWindowUrl) {}
