package com.sparta.ticket.reservation.seat.presentation.dto;

import com.sparta.ticket.reservation.seat.application.port.StoredHold;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * Redis 선점 조회 응답(#449). 결제 화면이 결제하기 전에 쓴다. 예약 조회(Reservation)에서 결제 화면이 쓰는 값과 같은 것만 준다.
 * status는 HELD(선점 중)·CONVERTING(결제하기 처리 중)·CONVERTED(예약으로 바뀜, reservationId 있음)다.
 * 시각은 좌석 API 공통으로 초 단위 {@code +09:00} 오프셋이다.
 */
public record SeatHoldViewResponse(
        String holdId,
        Long scheduleId,
        Long scheduleSeatId,
        String status,
        Long reservationId,
        OffsetDateTime holdExpiresAt,
        Integer basePrice,
        Long performanceId,
        String performanceTitle,
        String genre,
        OffsetDateTime scheduleStartAt,
        String gradeName) {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    public static SeatHoldViewResponse from(StoredHold hold) {
        LocalDateTime startAt = hold.validation().scheduleStartAt();
        return new SeatHoldViewResponse(
                hold.holdId(),
                hold.scheduleId(),
                hold.scheduleSeatId(),
                hold.state().name(),
                hold.reservationId(),
                offset(hold.expiresAt()),
                hold.basePrice(),
                hold.validation().performanceId(),
                hold.validation().performanceTitle(),
                hold.validation().genre(),
                startAt == null
                        ? null
                        : startAt.truncatedTo(ChronoUnit.SECONDS).atZone(SEOUL).toOffsetDateTime(),
                hold.validation().seats().get(0).gradeName());
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant.truncatedTo(ChronoUnit.SECONDS).atZone(SEOUL).toOffsetDateTime();
    }
}
