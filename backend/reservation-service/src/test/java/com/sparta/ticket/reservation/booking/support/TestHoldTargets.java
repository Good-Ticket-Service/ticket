package com.sparta.ticket.reservation.booking.support;

import com.sparta.ticket.reservation.booking.application.facade.HoldTarget;

/** 선점할 때 seat 모듈이 판매 검증 응답에서 꺼내 넘겨 주는 회차·공연·판매자. 테스트의 공연·판매자는 고정값이다. */
public final class TestHoldTargets {

    public static final Long PERFORMANCE_ID = 900L;
    public static final Long SELLER_ID = 700L;

    private TestHoldTargets() {}

    public static HoldTarget of(Long scheduleId) {
        return new HoldTarget(scheduleId, PERFORMANCE_ID, SELLER_ID);
    }
}
