package com.sparta.ticket.reservation.seat.domain;

/** 회차의 (삭제되지 않은) 좌석 수와 그중 판매 대상(is_sellable) 수. */
public record ScheduleSeatCounts(int seatCount, int sellableCount) {}
