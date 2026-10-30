package com.sparta.ticket.performance.presentation.seller.dto;

import com.sparta.ticket.performance.application.dto.SellerScheduleDetail;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** 판매자용 회차 응답(P13). 시각은 +09:00 오프셋을 붙인 ISO 8601이다. */
public record SellerScheduleResponse(
        Long id,
        Long performanceId,
        OffsetDateTime startAt,
        OffsetDateTime bookingOpenAt,
        OffsetDateTime bookingCloseAt,
        String operationStatus,
        String seatSetupStatus,
        boolean salesSuspended,
        String salesStatus,
        OffsetDateTime canceledAt,
        long seatConfigVersion,
        String seatSetupRequestId,
        String seatSetupError,
        String suspensionReason,
        String cancelReason,
        long version) {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    public static SellerScheduleResponse from(SellerScheduleDetail schedule) {
        return new SellerScheduleResponse(
                schedule.id(),
                schedule.performanceId(),
                offset(schedule.startAt()),
                offset(schedule.bookingOpenAt()),
                offset(schedule.bookingCloseAt()),
                schedule.operationStatus().name(),
                schedule.seatSetupStatus().name(),
                schedule.salesSuspended(),
                schedule.salesStatus().name(),
                offset(schedule.canceledAt()),
                schedule.seatConfigVersion(),
                schedule.seatSetupRequestId(),
                schedule.seatSetupError(),
                schedule.suspensionReason(),
                schedule.cancelReason(),
                schedule.version());
    }

    /** DB의 KST 시각에 +09:00 오프셋을 붙인다. */
    private static OffsetDateTime offset(LocalDateTime time) {
        return time == null ? null : time.atZone(SEOUL).toOffsetDateTime();
    }
}
