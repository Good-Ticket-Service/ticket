package com.sparta.ticket.reservation.coupon.application.dto;

import com.sparta.ticket.reservation.coupon.domain.Coupon;
import com.sparta.ticket.reservation.coupon.domain.CouponIssueStatus;
import com.sparta.ticket.reservation.coupon.domain.DiscountType;
import java.time.LocalDateTime;

/** 쿠폰 생성·수정·발급 중단 응답. 남은 수량과 발급 상태는 컬럼이 아니라 계산 값이다. */
public record CouponResult(
        Long couponId,
        String name,
        DiscountType discountType,
        Integer discountAmount,
        Integer discountRate,
        Integer maxDiscountAmount,
        Integer minBaseAmount,
        int totalQuantity,
        int remainingQuantity,
        LocalDateTime issueStartAt,
        LocalDateTime issueEndAt,
        int validDays,
        CouponIssueStatus issueStatus) {

    /**
     * 쿠폰과 현재 시각으로 응답을 만든다. 발급 상태는 현재 시각에 따라 달라지므로 시각을 함께 받는다.
     * 남은 수량은 호출자가 받은 회원 수로 계산해 넘긴다(CouponIssueState). 엔티티의 issued_quantity는 늦을 수 있다.
     */
    public static CouponResult of(Coupon coupon, int remainingQuantity, LocalDateTime now) {
        return new CouponResult(
                coupon.getId(),
                coupon.getName(),
                coupon.getDiscountPolicy().getDiscountType(),
                coupon.getDiscountPolicy().getDiscountAmount(),
                coupon.getDiscountPolicy().getDiscountRate(),
                coupon.getDiscountPolicy().getMaxDiscountAmount(),
                coupon.getDiscountPolicy().getMinBaseAmount(),
                coupon.getTotalQuantity(),
                remainingQuantity,
                coupon.getIssueStartAt(),
                coupon.getIssueEndAt(),
                coupon.getValidDays(),
                coupon.issueStatus(now));
    }
}
