package com.sparta.ticket.reservation.seat.support;

import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/** 테스트에서 캐시 만료 시계를 직접 흘리는 Ticker. 기다리지 않고 TTL이 지난 상황을 만든다. */
public class MutableTicker implements Ticker {

    private final AtomicLong nanos = new AtomicLong();

    @Override
    public long read() {
        return nanos.get();
    }

    public void advance(Duration duration) {
        nanos.addAndGet(duration.toNanos());
    }
}
