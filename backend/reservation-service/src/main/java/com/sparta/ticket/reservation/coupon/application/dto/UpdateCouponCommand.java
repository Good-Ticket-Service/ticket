package com.sparta.ticket.reservation.coupon.application.dto;

import com.sparta.ticket.reservation.coupon.domain.DiscountPolicy;
import java.time.LocalDateTime;

/**
 * 쿠폰 부분 수정. null인 항목은 바꾸지 않는다.
 * 할인 정책은 묶음 전체를 바꾼다. 보내지 않았으면 null이고, 보냈으면 검증을 마친 새 정책이다(#348).
 */
public record UpdateCouponCommand(
        String name,
        DiscountPolicy discountPolicy,
        Integer totalQuantity,
        LocalDateTime issueStartAt,
        LocalDateTime issueEndAt,
        Integer validDays) {}
