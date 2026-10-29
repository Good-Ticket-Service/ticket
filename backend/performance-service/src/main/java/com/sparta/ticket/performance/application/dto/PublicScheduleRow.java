package com.sparta.ticket.performance.application.dto;

import com.sparta.ticket.performance.domain.performance.OperationStatus;
import com.sparta.ticket.performance.domain.schedule.SeatSetupStatus;
import java.time.LocalDateTime;

/**
 * 공개 회차 조회에서 DB로 읽은 값. 판매 상태는 현재 시각이 필요해 {@link PublicSchedule}로 바꿀 때 계산한다. 시각은 KST다.
 *
 * @param performanceStatus 부모 공연의 운영 상태. 판매 상태 계산에만 쓰고 응답에는 넣지 않는다.
 */
public record PublicScheduleRow(
        Long id,
        Long performanceId,
        LocalDateTime startAt,
        LocalDateTime bookingOpenAt,
        LocalDateTime bookingCloseAt,
        OperationStatus operationStatus,
        SeatSetupStatus seatSetupStatus,
        boolean salesSuspended,
        LocalDateTime canceledAt,
        OperationStatus performanceStatus) {}
