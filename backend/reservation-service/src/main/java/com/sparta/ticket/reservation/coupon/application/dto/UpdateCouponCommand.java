package com.sparta.ticket.reservation.coupon.application.dto;

import java.time.LocalDateTime;

/** 쿠폰 부분 수정. null인 항목은 바꾸지 않는다. */
public record UpdateCouponCommand(
        String name,
        Integer discountAmount,
        Integer totalQuantity,
        LocalDateTime issueStartAt,
        LocalDateTime issueEndAt,
        Integer validDays) {}
