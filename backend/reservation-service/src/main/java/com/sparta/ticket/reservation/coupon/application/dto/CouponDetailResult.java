package com.sparta.ticket.reservation.coupon.application.dto;

import com.sparta.ticket.reservation.coupon.domain.Coupon;
import com.sparta.ticket.reservation.coupon.domain.CouponIssueStatus;
import java.time.LocalDateTime;

/** 쿠폰 목록·상세 응답. docs/api.md의 CouponDetail(= Coupon + issuedByMe)이다. */
public record CouponDetailResult(
        Long couponId,
        String name,
        int discountAmount,
        int totalQuantity,
        int remainingQuantity,
        LocalDateTime issueStartAt,
        LocalDateTime issueEndAt,
        int validDays,
        CouponIssueStatus issueStatus,
        // 요청한 사용자가 이 쿠폰을 이미 받았는지. 1인 1장이라 true면 다시 받을 수 없다(화면에서 "받기" 대신 "받음" 표시).
        // 사용·만료와 관계없이 한 번이라도 받았으면 true다. 받을 수 없는 SELLER·MASTER는 항상 false다.
        boolean issuedByMe) {

    /** 발급 상태는 현재 시각에 따라 달라지므로 시각을 함께 받는다. issuedByMe는 호출자가 회원 쿠폰을 조회해 넘긴다. */
    public static CouponDetailResult of(Coupon coupon, LocalDateTime now, boolean issuedByMe) {
        return new CouponDetailResult(
                coupon.getId(),
                coupon.getName(),
                coupon.getDiscountAmount(),
                coupon.getTotalQuantity(),
                coupon.getRemainingQuantity(),
                coupon.getIssueStartAt(),
                coupon.getIssueEndAt(),
                coupon.getValidDays(),
                coupon.issueStatus(now),
                issuedByMe);
    }
}
