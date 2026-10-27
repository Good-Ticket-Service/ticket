package com.sparta.ticket.reservation.booking.domain;

/** 선점한 회차 좌석과 선점 시점의 가격. seat 모듈이 선점할 때 넘겨 주고, 예약의 티켓에 그대로 남는다. */
public record SeatPrice(Long scheduleSeatId, int price) {}
