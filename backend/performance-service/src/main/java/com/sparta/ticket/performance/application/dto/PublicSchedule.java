package com.sparta.ticket.performance.application.dto;

import com.sparta.ticket.performance.domain.performance.OperationStatus;
import com.sparta.ticket.performance.domain.schedule.SalesStatus;
import com.sparta.ticket.performance.domain.schedule.SeatSetupStatus;
import java.time.LocalDateTime;

/** 공개 회차. 좌석 준비 오류·판매 중지 사유·version 같은 관리 정보는 담지 않는다. 시각은 KST다. */
public record PublicSchedule(
        Long id,
        Long performanceId,
        LocalDateTime startAt,
        LocalDateTime bookingOpenAt,
        LocalDateTime bookingCloseAt,
        OperationStatus operationStatus,
        SeatSetupStatus seatSetupStatus,
        boolean salesSuspended,
        SalesStatus salesStatus,
        LocalDateTime canceledAt) {

    /**
     * now 기준으로 판매 상태를 계산해 붙인다.
     *
     * @param performanceStatus 부모 공연의 운영 상태. 공연이 취소되면 회차도 CANCELED다. 응답에는 넣지 않는다.
     */
    public static PublicSchedule of(PublicScheduleRow row, OperationStatus performanceStatus, LocalDateTime now) {
        SalesStatus salesStatus = SalesStatus.of(
                performanceStatus,
                row.operationStatus(),
                row.bookingOpenAt(),
                row.bookingCloseAt(),
                row.salesSuspended(),
                row.seatSetupStatus(),
                now);
        return new PublicSchedule(
                row.id(),
                row.performanceId(),
                row.startAt(),
                row.bookingOpenAt(),
                row.bookingCloseAt(),
                row.operationStatus(),
                row.seatSetupStatus(),
                row.salesSuspended(),
                salesStatus,
                row.canceledAt());
    }
}
