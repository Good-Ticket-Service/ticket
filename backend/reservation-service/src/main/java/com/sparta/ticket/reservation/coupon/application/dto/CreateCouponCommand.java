package com.sparta.ticket.reservation.coupon.application.dto;

import java.time.LocalDateTime;

public record CreateCouponCommand(
        String name,
        int discountAmount,
        int totalQuantity,
        LocalDateTime issueStartAt,
        LocalDateTime issueEndAt,
        int validDays) {}
