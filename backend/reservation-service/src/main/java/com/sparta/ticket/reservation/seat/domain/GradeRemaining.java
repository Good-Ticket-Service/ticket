package com.sparta.ticket.reservation.seat.domain;

/**
 * 회차의 등급 하나에 남은 좌석 수. 판매 대상 좌석 중 AVAILABLE인 좌석만 센다.
 *
 * @param remaining 0이면 판매 대상 좌석이 모두 선점·판매된 것이다(화면은 "매진")
 */
public record GradeRemaining(Long venueSeatGradeId, String gradeName, long remaining) {}
