package com.sparta.ticket.payment.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

/** 시간 경계 시험에만 시간을 이동하며 각 테스트 전에 실제 현재 시각으로 되돌린다. */
public class MutablePaymentClock extends Clock {
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());
    private final ZoneId zone;

    public MutablePaymentClock() {
        this(ZoneId.of("Asia/Seoul"));
    }

    private MutablePaymentClock(ZoneId zone) {
        this.zone = zone;
    }

    public void reset() {
        now.set(Instant.now());
    }

    public void advance(Duration duration) {
        now.updateAndGet(value -> value.plus(duration));
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId other) {
        return Clock.fixed(instant(), other);
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}
