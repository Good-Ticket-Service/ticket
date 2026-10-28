package com.sparta.ticket.performance.support;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

/** 테스트가 현재 시각(KST)을 정할 수 있는 Clock. 운영 Clock과 같이 Asia/Seoul 기준이다. */
public class MutableClock extends Clock {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final ZoneId zone;
    private final AtomicReference<Instant> instant;

    public MutableClock(LocalDateTime start) {
        this(SEOUL, new AtomicReference<>(start.atZone(SEOUL).toInstant()));
    }

    private MutableClock(ZoneId zone, AtomicReference<Instant> instant) {
        this.zone = zone;
        this.instant = instant;
    }

    public void set(LocalDateTime now) {
        instant.set(now.atZone(zone).toInstant());
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
