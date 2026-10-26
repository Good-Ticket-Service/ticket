package com.sparta.ticket.reservation.support;

import com.sparta.ticket.reservation.domain.SeatPrice;
import java.util.Arrays;
import java.util.List;

/** 선점할 때 seat 모듈이 넘겨 주는 좌석과 가격. 테스트의 좌석 한 개 가격은 10,000원이다. */
public final class TestSeats {

    public static final int PRICE = 10_000;

    private TestSeats() {}

    public static List<SeatPrice> priced(Long... seatIds) {
        return Arrays.stream(seatIds).map(id -> new SeatPrice(id, PRICE)).toList();
    }
}
