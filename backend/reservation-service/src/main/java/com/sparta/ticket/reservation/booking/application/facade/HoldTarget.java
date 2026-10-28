package com.sparta.ticket.reservation.booking.application.facade;

/**
 * seat가 선점할 때 BookingFacade에 넘기는 회차와 그 회차의 공연·판매자. 세 값 모두 판매 검증 응답에서 꺼낸 값이다.
 * booking은 공연·판매자를 예약에 저장해 두고 결제 준비에 그대로 넘긴다. payment는 같은 orderId의 재요청에서
 * 이 값이 바뀌면 거절하므로, checkout 때 다시 조회하지 않고 선점 시점 값을 쓴다(#86).
 */
public record HoldTarget(Long scheduleId, Long performanceId, Long sellerId) {}
