package com.sparta.ticket.reservation.booking.infrastructure.coupon;

import com.sparta.ticket.reservation.booking.application.port.CouponPort;
import com.sparta.ticket.reservation.coupon.application.facade.CouponUsage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 같은 서비스의 coupon 모듈로 쿠폰을 잡고·쓰고·돌려준다(#125). 메서드 호출이라 예약 전이와 같은 트랜잭션에 합류한다. */
@Component
@RequiredArgsConstructor
public class CouponUsageAdapter implements CouponPort {

    private final CouponUsage couponUsage;

    @Override
    public long reserve(Long userCouponId, Long userId, Long reservationId) {
        return couponUsage.reserve(userCouponId, userId, reservationId).discountAmount();
    }

    @Override
    public void use(Long reservationId) {
        couponUsage.use(reservationId);
    }

    @Override
    public void release(Long reservationId) {
        couponUsage.release(reservationId);
    }
}
