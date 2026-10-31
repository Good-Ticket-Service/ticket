package com.sparta.ticket.performance.application.dto;

import com.sparta.ticket.performance.domain.performance.OperationStatus;
import com.sparta.ticket.performance.domain.schedule.SeatSetupStatus;
import java.time.LocalDateTime;

/**
 * 판매자 회차 조회에서 DB로 읽은 회차 값과 부모 공연의 판매자·운영 상태. 판매 상태는 현재 시각이 필요해
 * {@link SellerScheduleDetail}로 바꿀 때 계산한다. 시각은 KST다.
 */
public record SellerScheduleRow(
        Long id,
        Long performanceId,
        Long sellerId,
        OperationStatus performanceStatus,
        LocalDateTime startAt,
        LocalDateTime bookingOpenAt,
        LocalDateTime bookingCloseAt,
        OperationStatus operationStatus,
        SeatSetupStatus seatSetupStatus,
        boolean salesSuspended,
        LocalDateTime canceledAt,
        long seatConfigVersion,
        String seatSetupRequestId,
        String seatSetupError,
        String suspensionReason,
        String cancelReason,
        long version) {}
