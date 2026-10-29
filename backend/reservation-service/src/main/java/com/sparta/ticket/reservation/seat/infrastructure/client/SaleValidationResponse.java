package com.sparta.ticket.reservation.seat.infrastructure.client;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 판매 검증 응답(P21)에서 좌석이 쓰는 필드만 받는다. validatedAt은 쓰지 않아 무시한다.
 * scheduleStartAt은 공통 규칙대로 오프셋(+09:00)이 붙어 오므로 OffsetDateTime으로 받고, 어댑터가 KST 벽시계로 바꾼다.
 * 판매 거절도 HTTP 200이고 allowed=false, reasonCode, 빈 seats로 온다. allowed는 빠진 응답을 거절과 구분하려고 Boolean으로 받는다.
 */
public record SaleValidationResponse(
        Boolean allowed,
        String reasonCode,
        Long scheduleId,
        Long performanceId,
        Long sellerId,
        long configVersion,
        long policyVersion,
        OffsetDateTime scheduleStartAt,
        String performanceTitle,
        List<Seat> seats) {

    public record Seat(Long venueSeatId, Long venueSeatGradeId, String gradeName, int basePrice) {}
}
