package com.sparta.ticket.performance.presentation.dto;

import com.sparta.ticket.performance.application.dto.PublicSchedule;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 공개 회차 상세 응답의 data이자 회차 목록의 한 항목. 시각은 +09:00 오프셋을 붙인 ISO 8601로 내보낸다.
 * 좌석 준비 오류·판매 중지 사유·version은 넣지 않는다. canceledAt은 정상 회차이면 null이다.
 */
public record PublicScheduleResponse(
        Long id,
        Long performanceId,
        OffsetDateTime startAt,
        OffsetDateTime bookingOpenAt,
        OffsetDateTime bookingCloseAt,
        String operationStatus,
        String seatSetupStatus,
        boolean salesSuspended,
        String salesStatus,
        OffsetDateTime canceledAt) {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    public static PublicScheduleResponse from(PublicSchedule schedule) {
        return new PublicScheduleResponse(
                schedule.id(),
                schedule.performanceId(),
                offset(schedule.startAt()),
                offset(schedule.bookingOpenAt()),
                offset(schedule.bookingCloseAt()),
                schedule.operationStatus().name(),
                schedule.seatSetupStatus().name(),
                schedule.salesSuspended(),
                schedule.salesStatus().name(),
                offset(schedule.canceledAt()));
    }

    /** DB의 KST 시각에 +09:00 오프셋을 붙인다. */
    private static OffsetDateTime offset(LocalDateTime time) {
        return time == null ? null : time.atZone(SEOUL).toOffsetDateTime();
    }
}
