package com.sparta.ticket.reservation.coupon.application.dto;

import com.sparta.ticket.reservation.coupon.domain.CouponCategory;
import com.sparta.ticket.reservation.coupon.domain.DiscountPolicy;
import java.time.LocalDateTime;

/**
 * 쿠폰 생성에 필요한 값. Controller가 요청(CreateCouponRequest)을 검증한 뒤 이 형태로 Service에 넘긴다.
 * 할인 정책은 요청 값으로 만들 때(DiscountPolicy.of) 검증을 마친 값이다(#348).
 * 구분이 null이면 일반이고, 유효 일수는 웰컴 쿠폰일 때만 null일 수 있다(7로 채운다). 규칙은 Coupon.create가 검사한다(#357).
 */
public record CreateCouponCommand(
        String name,
        CouponCategory category,
        DiscountPolicy discountPolicy,
        int totalQuantity,
        LocalDateTime issueStartAt,
        LocalDateTime issueEndAt,
        Integer validDays) {

    /** 일반 쿠폰. 구분을 쓰지 않던 기존 호출(테스트 등)을 위해 둔다. */
    public CreateCouponCommand(
            String name,
            DiscountPolicy discountPolicy,
            int totalQuantity,
            LocalDateTime issueStartAt,
            LocalDateTime issueEndAt,
            int validDays) {
        this(name, CouponCategory.GENERAL, discountPolicy, totalQuantity, issueStartAt, issueEndAt, validDays);
    }

    /** 최소 적용 금액 없는 정액 일반 쿠폰. 정액만 쓰던 기존 호출(테스트 등)을 위해 둔다. */
    public CreateCouponCommand(
            String name,
            int discountAmount,
            int totalQuantity,
            LocalDateTime issueStartAt,
            LocalDateTime issueEndAt,
            int validDays) {
        this(name, DiscountPolicy.fixed(discountAmount), totalQuantity, issueStartAt, issueEndAt, validDays);
    }
}
