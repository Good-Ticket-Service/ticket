package com.sparta.ticket.reservation.seat.presentation.dto;

import com.sparta.ticket.reservation.seat.application.dto.SeatMapSnapshot;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * 회차 좌석 선택 가능 상태 응답(GET /api/seats/schedules/{scheduleId}/status). 시각은 초 단위, {@code +09:00} 오프셋 ISO 8601이다.
 *
 * @param selectableBitmap i번째 비트가 layout seats[i]의 선택 가능 여부다. 비트 규칙은 SeatMapSnapshot.of와 docs/api.md
 */
public record SeatStatusResponse(
        Long scheduleId, long configVersion, OffsetDateTime asOf, int seatCount, String selectableBitmap) {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    public static SeatStatusResponse from(SeatMapSnapshot snapshot) {
        return new SeatStatusResponse(
                snapshot.result().scheduleId(),
                snapshot.configVersion(),
                snapshot.result()
                        .asOf()
                        .truncatedTo(ChronoUnit.SECONDS)
                        .atZone(SEOUL)
                        .toOffsetDateTime(),
                snapshot.seatCount(),
                snapshot.selectableBitmap());
    }
}
