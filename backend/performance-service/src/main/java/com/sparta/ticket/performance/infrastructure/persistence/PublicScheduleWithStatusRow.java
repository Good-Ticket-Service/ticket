package com.sparta.ticket.performance.infrastructure.persistence;

import com.sparta.ticket.performance.domain.performance.OperationStatus;
import com.sparta.ticket.performance.domain.schedule.SeatSetupStatus;
import java.time.LocalDateTime;

/** 공개 회차 단건 조회 한 행. JPQL 생성자 표현식이 만들므로 public이다. */
public record PublicScheduleWithStatusRow(
        Long id,
        Long performanceId,
        LocalDateTime startAt,
        LocalDateTime bookingOpenAt,
        LocalDateTime bookingCloseAt,
        OperationStatus operationStatus,
        SeatSetupStatus seatSetupStatus,
        Boolean salesSuspended,
        LocalDateTime canceledAt,
        OperationStatus performanceStatus) {}
