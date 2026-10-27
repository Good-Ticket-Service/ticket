package com.sparta.ticket.reservation.booking.application.dto;

/** 결제 승인 입력. 금액은 받지 않는다. 준비 때 기록한 결제 금액을 쓴다. */
public record ConfirmCommand(String orderId, String pgAuthToken) {}
