package com.sparta.ticket.performance.domain.venue;

/**
 * 공연장 안 좌석 위치. DB 부분 UNIQUE(venue_id, section, row_label, seat_number)와 같은 기준이다.
 * Note: record라 equals·hashCode가 세 값으로 만들어져, Set으로 요청 안 중복과 기존 좌석 중복을 확인할 수 있다.
 */
public record SeatPosition(String section, String rowLabel, int seatNumber) {}
