package com.sparta.ticket.reservation.booking.support;

import com.sparta.ticket.reservation.booking.application.port.CouponPort;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.springframework.dao.CannotAcquireLockException;

/** 실제 쿠폰 구현에 위임하면서, 시험에 필요한 동시 진입과 잠금 실패만 reserve 앞에 끼워 넣는다. */
public class ControlledCouponPort implements CouponPort {

    private final CouponPort delegate;
    private volatile CyclicBarrier reserveBarrier;
    private volatile boolean failNextReserveWithLock;

    public ControlledCouponPort(CouponPort delegate) {
        this.delegate = delegate;
    }

    public void reset() {
        reserveBarrier = null;
        failNextReserveWithLock = false;
    }

    /** 여러 요청이 모두 reserve 직전에 도착한 뒤에 함께 진행한다. 앞선 release의 잠금을 쥔 채 겹치게 만든다. */
    public void syncReservesWith(CyclicBarrier barrier) {
        this.reserveBarrier = barrier;
    }

    /** 다음 reserve가 DB 교착으로 중단된 것처럼 잠금 실패 예외를 던진다. */
    public void failNextReserveWithLock() {
        this.failNextReserveWithLock = true;
    }

    @Override
    public long reserve(Long userCouponId, Long userId, Long reservationId) {
        awaitBarrier();
        if (failNextReserveWithLock) {
            failNextReserveWithLock = false;
            throw new CannotAcquireLockException("deadlock detected");
        }
        return delegate.reserve(userCouponId, userId, reservationId);
    }

    @Override
    public void use(Long reservationId) {
        delegate.use(reservationId);
    }

    @Override
    public void release(Long reservationId) {
        delegate.release(reservationId);
    }

    private void awaitBarrier() {
        CyclicBarrier barrier = reserveBarrier;
        if (barrier == null) {
            return;
        }
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("다른 요청이 reserve에 오지 않았다", e);
        }
    }
}
