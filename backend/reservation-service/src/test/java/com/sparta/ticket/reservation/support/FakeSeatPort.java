package com.sparta.ticket.reservation.support;

import com.sparta.ticket.reservation.application.port.SeatPort;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

/** 좌석 연산을 기록만 한다. 좌석 한 개 가격은 10,000원이다. events에는 호출 순서가 남는다. */
public class FakeSeatPort implements SeatPort {

    public static final long SEAT_PRICE = 10_000L;

    public final List<List<UUID>> locked = new CopyOnWriteArrayList<>();
    public final List<List<UUID>> sold = new CopyOnWriteArrayList<>();
    public final List<List<UUID>> released = new CopyOnWriteArrayList<>();
    public final List<String> events = new CopyOnWriteArrayList<>();
    private final Set<UUID> failingSeats = ConcurrentHashMap.newKeySet();
    private volatile CyclicBarrier lockBarrier;

    public void reset() {
        locked.clear();
        sold.clear();
        released.clear();
        events.clear();
        failingSeats.clear();
        lockBarrier = null;
    }

    /** 이 좌석을 잠그려 하면 실패한다. */
    public void failOnLock(UUID seatId) {
        failingSeats.add(seatId);
    }

    /** 여러 스레드가 모두 잠금 직전에 도착한 뒤에 함께 진행한다. 트랜잭션이 실제로 겹치게 만든다. */
    public void syncLocksWith(CyclicBarrier barrier) {
        this.lockBarrier = barrier;
    }

    @Override
    public void lockSeats(List<UUID> seatIds) {
        awaitBarrier();
        if (seatIds.stream().anyMatch(failingSeats::contains)) {
            throw new IllegalStateException("lock failed");
        }
        events.add("lock");
        locked.add(seatIds);
    }

    @Override
    public void sell(List<UUID> seatIds) {
        events.add("sell");
        sold.add(seatIds);
    }

    @Override
    public void release(List<UUID> seatIds) {
        events.add("release");
        released.add(seatIds);
    }

    @Override
    public long priceSnapshot(List<UUID> seatIds) {
        return SEAT_PRICE * seatIds.size();
    }

    private void awaitBarrier() {
        CyclicBarrier barrier = lockBarrier;
        if (barrier == null) {
            return;
        }
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("다른 스레드가 잠금에 오지 않았다", e);
        }
    }
}
