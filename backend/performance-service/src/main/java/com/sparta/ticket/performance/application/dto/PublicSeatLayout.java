package com.sparta.ticket.performance.application.dto;

import java.util.List;

/** 공개 정적 좌석 배치. 실시간 점유 상태는 포함하지 않는다. */
public record PublicSeatLayout(Long performanceId, VenueSummary venue, List<Seat> seats) {

    public record Seat(
            Long id,
            String section,
            String rowLabel,
            int seatNumber,
            Long venueSeatGradeId,
            String gradeName,
            boolean isSellable,
            Integer basePrice) {}
}
