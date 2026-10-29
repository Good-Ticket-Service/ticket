package com.sparta.ticket.reservation.seat.infrastructure.client;

import java.util.List;

/**
 * 판매 검증 응답(P21)에서 좌석이 쓰는 필드만 받는다. 나머지 필드(performanceTitle, scheduleStartAt, validatedAt)는 무시한다.
 * 판매 거절도 HTTP 200이고 allowed=false, reasonCode, 빈 seats로 온다.
 */
public record SaleValidationResponse(
        boolean allowed,
        String reasonCode,
        Long scheduleId,
        Long performanceId,
        Long sellerId,
        long configVersion,
        long policyVersion,
        List<Seat> seats) {

    public record Seat(Long venueSeatId, Long venueSeatGradeId, String gradeName, int basePrice) {}
}
