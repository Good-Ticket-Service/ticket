package com.sparta.ticket.reservation.seat.support;

import com.sparta.ticket.reservation.seat.application.hold.SeatHoldMode;
import com.sparta.ticket.reservation.seat.application.hold.SeatHoldModeSwitch;
import com.sparta.ticket.reservation.seat.application.hold.SeatHoldProperties;

/** 테스트에서 선점 저장 위치를 바꾸는 빈(#449). 컨텍스트를 함께 쓰므로 바꾼 테스트가 끝날 때 reset()으로 설정값에 되돌린다. */
public class MutableSeatHoldModeSwitch extends SeatHoldModeSwitch {

    private final SeatHoldMode configured;
    private volatile SeatHoldMode mode;

    public MutableSeatHoldModeSwitch(SeatHoldProperties properties) {
        super(properties);
        this.configured = properties.mode();
        this.mode = configured;
    }

    @Override
    public SeatHoldMode current() {
        return mode;
    }

    public void set(SeatHoldMode mode) {
        this.mode = mode;
    }

    private volatile boolean redisSeatState;

    /** Redis 점유 판정(#459)을 켠다. reset()이 끈다. */
    public void setRedisSeatState(boolean on) {
        this.redisSeatState = on;
    }

    @Override
    public boolean redisSeatState() {
        return redisSeatState;
    }

    public void reset() {
        this.mode = configured;
        this.redisSeatState = false;
    }
}
