package com.sparta.ticket.reservation.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

/** 테스트가 현재 시각을 정하고 앞으로 돌릴 수 있는 Clock. */
public class MutableClock extends Clock {

    private final ZoneId zone;
    private final AtomicReference<Instant> instant;

    public MutableClock(LocalDateTime start) {
        this(
                ZoneId.systemDefault(),
                new AtomicReference<>(start.atZone(ZoneId.systemDefault()).toInstant()));
    }

    private MutableClock(ZoneId zone, AtomicReference<Instant> instant) {
        this.zone = zone;
        this.instant = instant;
    }

    public void set(LocalDateTime now) {
        instant.set(now.atZone(zone).toInstant());
    }

    public void advance(Duration duration) {
        instant.updateAndGet(current -> current.plus(duration));
    }

    public LocalDateTime now() {
        return LocalDateTime.ofInstant(instant.get(), zone);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(zone, instant);
    }

    @Override
    public Instant instant() {
        return instant.get();
    }
}
