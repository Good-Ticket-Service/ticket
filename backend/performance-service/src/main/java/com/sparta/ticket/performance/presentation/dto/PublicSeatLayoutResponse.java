package com.sparta.ticket.performance.presentation.dto;

import com.sparta.ticket.performance.application.dto.PublicSeatLayout;
import com.sparta.ticket.performance.application.dto.VenueSummary;
import java.util.List;

/** P20 응답. 판매 제외 사유·판매자·버전 등 내부 정보는 담지 않는다. */
public record PublicSeatLayoutResponse(Long performanceId, VenueSummary venue, List<Seat> seats) {

    public static PublicSeatLayoutResponse from(PublicSeatLayout layout) {
        return new PublicSeatLayoutResponse(
                layout.performanceId(),
                layout.venue(),
                layout.seats().stream().map(Seat::from).toList());
    }

    public record Seat(
            Long id,
            String section,
            String rowLabel,
            int seatNumber,
            Long venueSeatGradeId,
            String gradeName,
            boolean isSellable,
            Integer basePrice) {
        private static Seat from(PublicSeatLayout.Seat seat) {
            return new Seat(
                    seat.id(),
                    seat.section(),
                    seat.rowLabel(),
                    seat.seatNumber(),
                    seat.venueSeatGradeId(),
                    seat.gradeName(),
                    seat.isSellable(),
                    seat.basePrice());
        }
    }
}
