package com.sparta.ticket.reservation.seat.presentation.dto;

import com.sparta.ticket.reservation.seat.application.dto.SelectableSeatResult;
import com.sparta.ticket.reservation.seat.application.dto.SelectableSeatsResult;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 선택 가능 좌석 조회 응답. 시각은 초 단위, {@code +09:00} 오프셋을 포함한 ISO 8601이다.
 *
 * <p>Note: Clock과 DB의 시각은 시간대 없는 KST 값이라, 응답에서 서울 오프셋을 붙인다.
 */
public record SelectableSeatsResponse(
        Long scheduleId, long configVersion, OffsetDateTime asOf, List<SelectableSeatResponse> seats) {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    public static SelectableSeatsResponse from(SelectableSeatsResult result) {
        return new SelectableSeatsResponse(
                result.scheduleId(),
                result.configVersion(),
                result.asOf().truncatedTo(ChronoUnit.SECONDS).atZone(SEOUL).toOffsetDateTime(),
                result.seats().stream().map(SelectableSeatResponse::from).toList());
    }

    public record SelectableSeatResponse(
            Long scheduleSeatId,
            Long venueSeatId,
            String section,
            String rowLabel,
            int seatNumber,
            Long venueSeatGradeId,
            String gradeName,
            Integer basePrice,
            boolean selectable) {

        static SelectableSeatResponse from(SelectableSeatResult seat) {
            return new SelectableSeatResponse(
                    seat.scheduleSeatId(),
                    seat.venueSeatId(),
                    seat.section(),
                    seat.rowLabel(),
                    seat.seatNumber(),
                    seat.venueSeatGradeId(),
                    seat.gradeName(),
                    seat.basePrice(),
                    seat.selectable());
        }
    }
}
