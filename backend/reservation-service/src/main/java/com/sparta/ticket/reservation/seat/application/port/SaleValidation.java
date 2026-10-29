package com.sparta.ticket.reservation.seat.application.port;

import java.util.List;

/** 판매 검증 결과. 거절이면 reasonCode가 있고 seats는 비어 있다. */
public record SaleValidation(
        boolean allowed,
        String reasonCode,
        Long scheduleId,
        Long performanceId,
        Long sellerId,
        long configVersion,
        long policyVersion,
        List<SaleValidationSeat> seats) {}
