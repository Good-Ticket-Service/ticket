package com.sparta.ticket.reservation.seat.support;

import com.sparta.ticket.reservation.seat.application.hold.SeatHoldLockMode;
import com.sparta.ticket.reservation.seat.application.hold.SeatHoldProperties;
import com.sparta.ticket.reservation.seat.application.hold.SeatHoldProperties.LockMode;

/** 테스트에서 선점 잠금 방식을 바꾸는 빈. 컨텍스트를 함께 쓰므로 바꾼 테스트가 끝날 때 reset()으로 설정값에 되돌린다. */
public class MutableSeatHoldLockMode extends SeatHoldLockMode {

    private final LockMode configured;
    private volatile LockMode lockMode;

    public MutableSeatHoldLockMode(SeatHoldProperties properties) {
        super(properties);
        this.configured = properties.lockMode();
        this.lockMode = configured;
    }

    @Override
    public LockMode current() {
        return lockMode;
    }

    public void set(LockMode lockMode) {
        this.lockMode = lockMode;
    }

    public void reset() {
        this.lockMode = configured;
    }
}
