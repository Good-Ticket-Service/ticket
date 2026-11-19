package com.sparta.ticket.reservation.seat.presentation.dto;

import com.sparta.ticket.reservation.seat.application.hold.SeatHoldResult;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * 좌석 선점 응답. status는 선점한 좌석의 상태라 항상 HOLD다.
 * holdExpiresAt은 예약과 좌석에 같은 값으로 저장한 만료 시각이며, 초 단위 {@code +09:00} 오프셋으로 내보낸다(좌석 API 공통).
 */
public record SeatHoldResponse(
        Long reservationId, Long scheduleSeatId, String status, OffsetDateTime holdExpiresAt, Integer basePrice) {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    public static SeatHoldResponse from(SeatHoldResult result) {
        return new SeatHoldResponse(
                result.reservationId(),
                result.scheduleSeatId(),
                "HOLD",
                result.holdExpiresAt()
                        .truncatedTo(ChronoUnit.SECONDS)
                        .atZone(SEOUL)
                        .toOffsetDateTime(),
                result.basePrice());
    }
}
