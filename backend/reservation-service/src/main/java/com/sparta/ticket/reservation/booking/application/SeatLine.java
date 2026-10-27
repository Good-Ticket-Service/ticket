package com.sparta.ticket.reservation.booking.application;

/**
 * seat가 선점할 때 BookingFacade에 넘기는 좌석 한 줄. 회차 좌석 id와 선점 시점의 가격이다.
 * Facade로 주고받는 타입은 booking의 domain 타입을 쓰지 않는다. seat가 booking 안쪽에 묶이지 않고,
 * 나중에 Facade를 Feign으로 바꿀 때 이 타입이 그대로 요청 DTO가 된다.
 */
public record SeatLine(Long scheduleSeatId, int price) {}
