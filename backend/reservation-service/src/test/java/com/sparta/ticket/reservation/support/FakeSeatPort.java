package com.sparta.ticket.reservation.support;

import com.sparta.ticket.reservation.application.port.SeatPort;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** 좌석 연산을 기록만 한다. 좌석 한 개 가격은 10,000원이다. */
public class FakeSeatPort implements SeatPort {

    public static final long SEAT_PRICE = 10_000L;

    public final List<List<UUID>> locked = new CopyOnWriteArrayList<>();
    public final List<List<UUID>> sold = new CopyOnWriteArrayList<>();
    public final List<List<UUID>> released = new CopyOnWriteArrayList<>();

    public void reset() {
        locked.clear();
        sold.clear();
        released.clear();
    }

    @Override
    public void lockSeats(List<UUID> seatIds) {
        locked.add(seatIds);
    }

    @Override
    public void sell(List<UUID> seatIds) {
        sold.add(seatIds);
    }

    @Override
    public void release(List<UUID> seatIds) {
        released.add(seatIds);
    }

    @Override
    public long priceSnapshot(List<UUID> seatIds) {
        return SEAT_PRICE * seatIds.size();
    }
}
