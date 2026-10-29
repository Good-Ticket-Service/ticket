package com.sparta.ticket.performance.application.dto;

import com.sparta.ticket.performance.domain.performance.OperationStatus;
import com.sparta.ticket.performance.domain.performance.PublicationStatus;
import com.sparta.ticket.performance.domain.schedule.SaleCondition;
import com.sparta.ticket.performance.domain.schedule.SeatSetupStatus;
import java.time.LocalDateTime;

/**
 * 판매 검증 대상 회차와 부모 공연을 한 번에 읽은 값. 시각은 KST다.
 *
 * @param performanceVersion 부모 공연의 version. 응답의 policyVersion이다.
 */
public record SaleTarget(
        Long scheduleId,
        Long performanceId,
        Long sellerId,
        Long venueId,
        String performanceTitle,
        LocalDateTime startAt,
        LocalDateTime bookingOpenAt,
        LocalDateTime bookingCloseAt,
        PublicationStatus publicationStatus,
        OperationStatus performanceStatus,
        Long performanceVersion,
        OperationStatus scheduleStatus,
        boolean salesSuspended,
        SeatSetupStatus seatSetupStatus,
        long seatConfigVersion) {

    public SaleCondition condition() {
        return new SaleCondition(
                publicationStatus,
                performanceStatus,
                scheduleStatus,
                bookingOpenAt,
                bookingCloseAt,
                salesSuspended,
                seatSetupStatus,
                seatConfigVersion);
    }
}
