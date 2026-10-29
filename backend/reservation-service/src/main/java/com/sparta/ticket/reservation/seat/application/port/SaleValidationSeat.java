package com.sparta.ticket.reservation.seat.application.port;

/** 판매 검증이 허용한 좌석의 등급과 공연별 등급 가격. */
public record SaleValidationSeat(Long venueSeatId, Long venueSeatGradeId, String gradeName, int basePrice) {}
