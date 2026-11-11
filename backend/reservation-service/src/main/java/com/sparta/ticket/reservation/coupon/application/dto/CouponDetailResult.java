package com.sparta.ticket.reservation.coupon.application.dto;

import com.sparta.ticket.reservation.coupon.domain.Coupon;
import com.sparta.ticket.reservation.coupon.domain.CouponIssueStatus;
import com.sparta.ticket.reservation.coupon.domain.DiscountType;
import java.time.LocalDateTime;

/** 쿠폰 목록·상세 응답. docs/api.md의 CouponDetail(= Coupon + issuedByMe)이다. */
public record CouponDetailResult(
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
        CouponIssueStatus issueStatus,
        // 요청한 사용자가 이 쿠폰을 이미 받았는지. 1인 1장이라 true면 다시 받을 수 없다(화면에서 "받기" 대신 "받음" 표시).
        // 사용·만료와 관계없이 한 번이라도 받았으면 true다. 받을 수 없는 SELLER·MASTER는 항상 false다.
        boolean issuedByMe) {

    /**
     * 발급 상태는 현재 시각에 따라 달라지므로 시각을 함께 받는다. issuedByMe는 호출자가 회원 쿠폰을 조회해 넘긴다.
     * 남은 수량은 호출자가 받은 회원 수로 계산해 넘긴다(CouponIssueState). 엔티티의 issued_quantity는 늦을 수 있다.
     */
    public static CouponDetailResult of(Coupon coupon, int remainingQuantity, LocalDateTime now, boolean issuedByMe) {
        return new CouponDetailResult(
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
                coupon.issueStatus(now),
                issuedByMe);
    }
}
