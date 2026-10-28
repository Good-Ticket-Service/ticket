package com.sparta.ticket.reservation.booking.domain;

/**
 * 예약이 무엇을 샀는지: 회차와 그 회차의 공연·판매자. 공연·판매자는 선점 시점 값이며 결제 준비에 그대로 넘긴다.
 * payment는 같은 orderId의 재요청에서 두 값이 바뀌면 거절하므로 예약이 끝날 때까지 바꾸지 않는다.
 */
public record SaleTarget(Long scheduleId, Long performanceId, Long sellerId) {}
