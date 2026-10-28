package com.sparta.ticket.reservation.coupon.application.dto;

import com.sparta.ticket.reservation.coupon.domain.UserCoupon;
import com.sparta.ticket.reservation.coupon.domain.UserCouponStatus;
import java.time.LocalDateTime;

/** 내 쿠폰 목록 응답. docs/api.md의 UserCoupon이다. 발급 응답(IssuedCouponResult)과 달리 usedAt을 포함한다. */
public record UserCouponResult(
        Long userCouponId,
        Long couponId,
        String name,
        int discountAmount,
        UserCouponStatus status,
        LocalDateTime issuedAt,
        LocalDateTime expiresAt,
        LocalDateTime usedAt) {

    /**
     * 상태는 저장된 값이 아니라 보여 줄 상태(displayStatus)다. 만료 배치 전이어도 기간이 지난 쿠폰은 EXPIRED로 보인다.
     * 쿠폰 이름은 회원 쿠폰에 복사하지 않으므로 호출자가 쿠폰 정의에서 찾아 넘긴다.
     */
    public static UserCouponResult of(UserCoupon userCoupon, String couponName, LocalDateTime now) {
        return new UserCouponResult(
                userCoupon.getId(),
                userCoupon.getCouponId(),
                couponName,
                userCoupon.getDiscountAmount(),
                userCoupon.displayStatus(now),
                userCoupon.getIssuedAt(),
                userCoupon.getExpiresAt(),
                userCoupon.getUsedAt());
    }
}
