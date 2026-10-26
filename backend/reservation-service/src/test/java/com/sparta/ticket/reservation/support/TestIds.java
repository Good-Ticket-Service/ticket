package com.sparta.ticket.reservation.support;

import java.util.concurrent.atomic.AtomicLong;

/** 회차·좌석처럼 다른 모듈이 만드는 BIGINT ID를 테스트끼리 겹치지 않게 만든다. */
public final class TestIds {

    private static final AtomicLong NEXT = new AtomicLong(1_000);

    private TestIds() {}

    public static long next() {
        return NEXT.incrementAndGet();
    }
}
