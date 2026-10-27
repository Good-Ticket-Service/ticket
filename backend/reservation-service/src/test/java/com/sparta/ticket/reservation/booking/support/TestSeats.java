package com.sparta.ticket.reservation.booking.support;

import com.sparta.ticket.reservation.booking.application.SeatLine;
import java.util.Arrays;
import java.util.List;

/** 선점할 때 seat 모듈이 넘겨 주는 좌석과 가격. 테스트의 좌석 한 개 가격은 10,000원이다. */
public final class TestSeats {

    public static final int PRICE = 10_000;

    private TestSeats() {}

    public static List<SeatLine> priced(Long... seatIds) {
        return Arrays.stream(seatIds).map(id -> new SeatLine(id, PRICE)).toList();
    }
}
