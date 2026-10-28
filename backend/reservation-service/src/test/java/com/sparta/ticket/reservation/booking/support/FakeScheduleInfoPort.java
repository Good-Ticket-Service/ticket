package com.sparta.ticket.reservation.booking.support;

import com.sparta.ticket.reservation.booking.application.port.ScheduleInfoPort;

/** 회차 판매 정보 대역. 회차 ID와 상관없이 같은 공연·판매자를 돌려준다. */
public class FakeScheduleInfoPort implements ScheduleInfoPort {

    public static final long PERFORMANCE_ID = 900L;
    public static final long SELLER_ID = 800L;

    @Override
    public ScheduleInfo find(Long scheduleId) {
        return new ScheduleInfo(PERFORMANCE_ID, SELLER_ID);
    }
}
