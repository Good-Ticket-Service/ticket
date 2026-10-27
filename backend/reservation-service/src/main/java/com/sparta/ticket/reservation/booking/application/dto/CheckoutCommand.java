package com.sparta.ticket.reservation.booking.application.dto;

/** 결제 준비 입력. 금액은 받지 않는다. 서버가 좌석 가격으로 계산하고 쿠폰은 payment가 적용한다. */
public record CheckoutCommand(Long couponId, String pgProvider) {}
