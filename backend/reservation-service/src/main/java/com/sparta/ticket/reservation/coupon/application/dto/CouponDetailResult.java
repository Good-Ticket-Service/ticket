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
