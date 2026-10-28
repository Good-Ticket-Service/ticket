package com.sparta.ticket.reservation.booking.application.dto;

/** 결제 준비 입력. 금액은 받지 않는다. 서버가 좌석 가격과 쿠폰으로 계산한다. */
public record CheckoutCommand(Long userCouponId, String pgProvider) {}
