package com.sparta.ticket.performance.domain.schedule;

import com.sparta.ticket.performance.domain.performance.OperationStatus;
import java.time.LocalDateTime;

/**
 * 회차의 판매 상태. DB에 저장하지 않고 조회할 때 계산한다. 매진 여부는 넣지 않는다(예매 서비스가 따로 조회).
 * 명세는 .local/performance/api.md 「salesStatus 계산 규칙」이다.
 */
public enum SalesStatus {
    CANCELED,
    CLOSED,
    BEFORE_OPEN,
    UNAVAILABLE,
    ON_SALE;

    /**
     * 위 순서대로 먼저 맞는 값을 준다. 예매 마감 시각과 같으면 CLOSED, 예매 오픈 시각과 같으면 열린 것으로 본다.
     *
     * @param performanceStatus 부모 공연의 운영 상태. 공연이 취소되면 회차도 CANCELED다.
     */
    public static SalesStatus of(
            OperationStatus performanceStatus,
            OperationStatus scheduleStatus,
            LocalDateTime bookingOpenAt,
            LocalDateTime bookingCloseAt,
            boolean salesSuspended,
            SeatSetupStatus seatSetupStatus,
            LocalDateTime now) {
        if (performanceStatus == OperationStatus.CANCELED || scheduleStatus == OperationStatus.CANCELED) {
            return CANCELED;
        }
        if (!now.isBefore(bookingCloseAt)) {
            return CLOSED;
        }
        if (now.isBefore(bookingOpenAt)) {
            return BEFORE_OPEN;
        }
        if (salesSuspended || seatSetupStatus != SeatSetupStatus.READY) {
            return UNAVAILABLE;
        }
        return ON_SALE;
    }
}
