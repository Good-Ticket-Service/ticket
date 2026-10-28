package com.sparta.ticket.reservation.booking.support;

import com.sparta.ticket.reservation.booking.application.port.SeatPort;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

/**
 * 좌석 연산을 기록한다. events에는 호출 순서가, soldFor·releasedFor에는 좌석을 바꾼 예약이 남는다.
 * 기본은 좌석이 모두 이 예약의 HOLD라고 답한다. useReal을 부르면 기록하면서 실제 좌석 어댑터에도 넘긴다.
 */
public class FakeSeatPort implements SeatPort {

    public final List<List<Long>> locked = new CopyOnWriteArrayList<>();
    public final List<List<Long>> sold = new CopyOnWriteArrayList<>();
    public final List<List<Long>> released = new CopyOnWriteArrayList<>();
    public final List<Long> soldFor = new CopyOnWriteArrayList<>();
    public final List<Long> releasedFor = new CopyOnWriteArrayList<>();
    public final List<String> events = new CopyOnWriteArrayList<>();
    private final Set<Long> failingSeats = ConcurrentHashMap.newKeySet();
    private volatile CyclicBarrier lockBarrier;
    private final Set<Long> notHeldSeats = ConcurrentHashMap.newKeySet();
    private volatile SeatPort real;

    public void reset() {
        locked.clear();
        sold.clear();
        released.clear();
        soldFor.clear();
        releasedFor.clear();
        events.clear();
        failingSeats.clear();
        lockBarrier = null;
        notHeldSeats.clear();
        real = null;
    }

    /** 이 좌석을 잠그려 하면 실패한다. */
    public void failOnLock(Long seatId) {
        failingSeats.add(seatId);
    }

    /** 이 좌석을 잠그면 이 예약의 선점이 아니라고 답한다. */
    public void notHeld(Long seatId) {
        notHeldSeats.add(seatId);
    }

    /** 기록하면서 실제 좌석 어댑터에도 넘긴다. 좌석 테이블까지 바뀌는지 보는 테스트가 쓴다. */
    public void useReal(SeatPort real) {
        this.real = real;
    }

    /** 여러 스레드가 모두 잠금 직전에 도착한 뒤에 함께 진행한다. 트랜잭션이 실제로 겹치게 만든다. */
    public void syncLocksWith(CyclicBarrier barrier) {
        this.lockBarrier = barrier;
    }

    @Override
    public boolean lockSeats(List<Long> seatIds, Long reservationId) {
        awaitBarrier();
        if (seatIds.stream().anyMatch(failingSeats::contains)) {
            throw new IllegalStateException("lock failed");
        }
        events.add("lock");
        locked.add(seatIds);
        if (real != null) {
            return real.lockSeats(seatIds, reservationId);
        }
        return seatIds.stream().noneMatch(notHeldSeats::contains);
    }

    @Override
    public void sell(List<Long> seatIds, Long reservationId) {
        events.add("sell");
        sold.add(seatIds);
        soldFor.add(reservationId);
        if (real != null) {
            real.sell(seatIds, reservationId);
        }
    }

    @Override
    public void release(List<Long> seatIds, Long reservationId) {
        events.add("release");
        released.add(seatIds);
        releasedFor.add(reservationId);
        if (real != null) {
            real.release(seatIds, reservationId);
        }
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
