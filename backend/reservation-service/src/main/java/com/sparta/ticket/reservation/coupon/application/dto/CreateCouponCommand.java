package com.sparta.ticket.reservation.coupon.application.dto;

import com.sparta.ticket.reservation.coupon.domain.DiscountPolicy;
import java.time.LocalDateTime;

/**
 * 쿠폰 생성에 필요한 값. Controller가 요청(CreateCouponRequest)을 검증한 뒤 이 형태로 Service에 넘긴다.
 * 할인 정책은 요청 값으로 만들 때(DiscountPolicy.of) 검증을 마친 값이다(#348).
 */
public record CreateCouponCommand(
        String name,
        DiscountPolicy discountPolicy,
        int totalQuantity,
        LocalDateTime issueStartAt,
        LocalDateTime issueEndAt,
        int validDays) {

    /** 최소 적용 금액 없는 정액 쿠폰. 정액만 쓰던 기존 호출(테스트 등)을 위해 둔다. */
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
