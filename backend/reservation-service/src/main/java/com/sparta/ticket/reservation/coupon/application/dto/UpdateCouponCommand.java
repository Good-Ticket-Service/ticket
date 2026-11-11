package com.sparta.ticket.reservation.coupon.application.dto;

import com.sparta.ticket.reservation.coupon.domain.CouponCategory;
import com.sparta.ticket.reservation.coupon.domain.DiscountPolicy;
import java.time.LocalDateTime;

/**
 * 쿠폰 부분 수정. null인 항목은 바꾸지 않는다.
 * 할인 정책은 묶음 전체를 바꾼다. 보내지 않았으면 null이고, 보냈으면 검증을 마친 새 정책이다(#348).
 * 구분(#357)도 보내지 않았으면 null이다.
 */
public record UpdateCouponCommand(
        String name,
        CouponCategory category,
        DiscountPolicy discountPolicy,
        Integer totalQuantity,
        LocalDateTime issueStartAt,
        LocalDateTime issueEndAt,
        Integer validDays) {

    /** 구분은 바꾸지 않는 수정. 구분을 쓰지 않던 기존 호출(테스트 등)을 위해 둔다. */
    public UpdateCouponCommand(
            String name,
            DiscountPolicy discountPolicy,
            Integer totalQuantity,
            LocalDateTime issueStartAt,
            LocalDateTime issueEndAt,
            Integer validDays) {
        this(name, null, discountPolicy, totalQuantity, issueStartAt, issueEndAt, validDays);
    }
}
