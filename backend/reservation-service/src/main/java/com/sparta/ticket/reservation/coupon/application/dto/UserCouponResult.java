package com.sparta.ticket.reservation.coupon.application.dto;

import com.sparta.ticket.reservation.coupon.domain.Coupon;
import com.sparta.ticket.reservation.coupon.domain.CouponGenre;
import com.sparta.ticket.reservation.coupon.domain.DiscountType;
import com.sparta.ticket.reservation.coupon.domain.UserCoupon;
import com.sparta.ticket.reservation.coupon.domain.UserCouponStatus;
import java.time.LocalDateTime;

/** 내 쿠폰 목록 응답. docs/api.md의 UserCoupon이다. 발급 응답(IssuedCouponResult)과 달리 usedAt을 포함한다. */
public record UserCouponResult(
        Long userCouponId,
        Long couponId,
        String name,
        // 쓸 수 있는 공연 장르. 쿠폰 정의에서 읽고, null이면 모든 장르(#389)
        CouponGenre genre,
        DiscountType discountType,
        Integer discountAmount,
        Integer discountRate,
        Integer maxDiscountAmount,
        Integer minBaseAmount,
        UserCouponStatus status,
        LocalDateTime issuedAt,
        LocalDateTime expiresAt,
        LocalDateTime usedAt) {

    /**
     * 상태는 저장된 값이 아니라 보여 줄 상태(displayStatus)다. 만료 배치가 아직 처리하지 않았어도 기간이 지난 쿠폰은 EXPIRED로 보인다.
     * 쿠폰 이름·장르는 회원 쿠폰에 복사하지 않으므로 호출자가 쿠폰 정의를 찾아 넘긴다. 정의가 없으면 null이고 둘 다 null로 둔다.
     */
    public static UserCouponResult of(UserCoupon userCoupon, Coupon coupon, LocalDateTime now) {
        return new UserCouponResult(
                userCoupon.getId(),
                userCoupon.getCouponId(),
                coupon == null ? null : coupon.getName(),
                coupon == null ? null : coupon.getGenre(),
                userCoupon.getDiscountPolicy().getDiscountType(),
                userCoupon.getDiscountPolicy().getDiscountAmount(),
                userCoupon.getDiscountPolicy().getDiscountRate(),
                userCoupon.getDiscountPolicy().getMaxDiscountAmount(),
                userCoupon.getDiscountPolicy().getMinBaseAmount(),
                userCoupon.displayStatus(now),
                userCoupon.getIssuedAt(),
                userCoupon.getExpiresAt(),
                userCoupon.getUsedAt());
    }
}
