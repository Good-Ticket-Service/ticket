package com.sparta.ticket.reservation.coupon.application.dto;

import java.time.LocalDateTime;

/** 쿠폰 생성에 필요한 값. Controller가 요청(CreateCouponRequest)을 검증한 뒤 이 형태로 Service에 넘긴다. */
public record CreateCouponCommand(
        String name,
        int discountAmount,
        int totalQuantity,
        LocalDateTime issueStartAt,
        LocalDateTime issueEndAt,
        int validDays) {}
