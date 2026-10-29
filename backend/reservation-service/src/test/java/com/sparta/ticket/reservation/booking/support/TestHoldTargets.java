package com.sparta.ticket.reservation.booking.support;

import com.sparta.ticket.reservation.booking.application.facade.HoldTarget;
import java.time.LocalDateTime;

/**
 * 선점할 때 seat 모듈이 판매 검증 응답에서 꺼내 넘겨 주는 회차·공연·판매자·시작 시각·공연명.
 * 테스트의 공연·판매자·공연명은 고정값이고, 회차는 테스트 시계 시작보다 하루 뒤에 시작한다.
 */
public final class TestHoldTargets {

    public static final Long PERFORMANCE_ID = 900L;
    public static final Long SELLER_ID = 700L;
    public static final LocalDateTime SCHEDULE_START_AT = BookingTestConfig.START_TIME.plusDays(1);
    public static final String PERFORMANCE_TITLE = "테스트 공연";

    private TestHoldTargets() {}

    public static HoldTarget of(Long scheduleId) {
        return startingAt(scheduleId, SCHEDULE_START_AT);
    }

    public static HoldTarget startingAt(Long scheduleId, LocalDateTime scheduleStartAt) {
        return new HoldTarget(scheduleId, PERFORMANCE_ID, SELLER_ID, scheduleStartAt, PERFORMANCE_TITLE);
    }
}
