package com.sparta.ticket.reservation.seat.presentation.dto;

import com.sparta.ticket.reservation.seat.application.dto.SeatMapSnapshot;
import com.sparta.ticket.reservation.seat.application.dto.SelectableSeatResult;
import java.util.List;

/**
 * 회차 좌석 배치 응답(GET /api/seats/schedules/{scheduleId}/layout). 선택 가능 여부는 담지 않고 status 비트맵으로 따로 준다.
 * seats는 좌석 id 오름차순이고, status 비트맵의 i번째 비트가 seats[i]다. configVersion이 같으면 이 응답도 같다.
 */
public record SeatLayoutResponse(Long scheduleId, long configVersion, List<SeatLayoutSeat> seats) {

    public static SeatLayoutResponse from(SeatMapSnapshot snapshot) {
        return new SeatLayoutResponse(
                snapshot.result().scheduleId(),
                snapshot.configVersion(),
                snapshot.result().seats().stream().map(SeatLayoutSeat::from).toList());
    }

    /** venueSeatId는 공연장 좌석 배치(P20)와 짝짓는 키다. */
    public record SeatLayoutSeat(
            Long scheduleSeatId,
            Long venueSeatId,
            String section,
            String rowLabel,
            int seatNumber,
            Long venueSeatGradeId,
            String gradeName,
            Integer basePrice) {

        static SeatLayoutSeat from(SelectableSeatResult seat) {
            return new SeatLayoutSeat(
                    seat.scheduleSeatId(),
                    seat.venueSeatId(),
                    seat.section(),
                    seat.rowLabel(),
                    seat.seatNumber(),
                    seat.venueSeatGradeId(),
                    seat.gradeName(),
                    seat.basePrice());
        }
    }
}
