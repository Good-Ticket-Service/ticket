package com.sparta.ticket.reservation.coupon.application.dto;

import com.sparta.ticket.reservation.coupon.domain.Coupon;
import com.sparta.ticket.reservation.coupon.domain.UserCoupon;
import com.sparta.ticket.reservation.coupon.domain.UserCouponStatus;
import java.time.LocalDateTime;

/** 쿠폰 발급 응답. docs/api.md의 UserCoupon에서 usedAt을 뺀 형태다. 막 발급한 쿠폰은 쓴 적이 없기 때문이다. */
public record IssuedCouponResult(
        Long userCouponId,
        Long couponId,
        String name,
        int discountAmount,
        UserCouponStatus status,
        LocalDateTime issuedAt,
        LocalDateTime expiresAt) {

    /** 쿠폰 이름은 회원 쿠폰에 복사하지 않으므로 쿠폰 정의에서 가져온다. */
    public static IssuedCouponResult of(UserCoupon userCoupon, Coupon coupon) {
        return new IssuedCouponResult(
                userCoupon.getId(),
                userCoupon.getCouponId(),
                coupon.getName(),
                userCoupon.getDiscountAmount(),
                userCoupon.getStatus(),
                userCoupon.getIssuedAt(),
                userCoupon.getExpiresAt());
    }
}
