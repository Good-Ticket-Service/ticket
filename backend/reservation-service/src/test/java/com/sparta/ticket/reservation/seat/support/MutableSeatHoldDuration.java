package com.sparta.ticket.reservation.seat.support;

import com.sparta.ticket.reservation.seat.application.hold.SeatHoldDuration;
import com.sparta.ticket.reservation.seat.application.hold.SeatHoldProperties;
import java.time.Duration;

/** 테스트에서 선점 유지 시간을 바꾸는 빈(#248). 컨텍스트를 함께 쓰므로 바꾼 테스트가 끝날 때 reset()으로 설정값에 되돌린다. */
public class MutableSeatHoldDuration extends SeatHoldDuration {

    private final Duration configured;
    private volatile Duration duration;

    public MutableSeatHoldDuration(SeatHoldProperties properties) {
        super(properties);
        this.configured = properties.duration();
        this.duration = configured;
    }

    @Override
    public Duration current() {
        return duration;
    }

    public void set(Duration duration) {
        this.duration = duration;
    }

    public void reset() {
        this.duration = configured;
    }
}
