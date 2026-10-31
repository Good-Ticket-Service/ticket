package com.sparta.ticket.performance.application.dto;

import com.sparta.ticket.performance.domain.performance.OperationStatus;
import com.sparta.ticket.performance.domain.schedule.SalesStatus;
import com.sparta.ticket.performance.domain.schedule.Schedule;
import com.sparta.ticket.performance.domain.schedule.SeatSetupStatus;
import java.time.LocalDateTime;

/** 판매자용 회차 상세(P13). 공개 응답과 달리 좌석 준비 오류·판매 중지 사유·version을 담는다. */
public record SellerScheduleDetail(
        Long id,
        Long performanceId,
        LocalDateTime startAt,
        LocalDateTime bookingOpenAt,
        LocalDateTime bookingCloseAt,
        OperationStatus operationStatus,
        SeatSetupStatus seatSetupStatus,
        boolean salesSuspended,
        SalesStatus salesStatus,
        LocalDateTime canceledAt,
        long seatConfigVersion,
        String seatSetupRequestId,
        String seatSetupError,
        String suspensionReason,
        String cancelReason,
        long version) {

    /** salesStatus는 저장하지 않고 부모 공연의 운영 상태와 지금 시각으로 계산한다. */
    public static SellerScheduleDetail of(Schedule schedule, OperationStatus performanceStatus, LocalDateTime now) {
        return new SellerScheduleDetail(
                schedule.getId(),
                schedule.getPerformanceId(),
                schedule.getStartAt(),
                schedule.getBookingOpenAt(),
                schedule.getBookingCloseAt(),
                schedule.getOperationStatus(),
                schedule.getSeatSetupStatus(),
                schedule.isSalesSuspended(),
                SalesStatus.of(
                        performanceStatus,
                        schedule.getOperationStatus(),
                        schedule.getBookingOpenAt(),
                        schedule.getBookingCloseAt(),
                        schedule.isSalesSuspended(),
                        schedule.getSeatSetupStatus(),
                        now),
                schedule.getCanceledAt(),
                schedule.getSeatConfigVersion(),
                schedule.getSeatSetupRequestId(),
                schedule.getSeatSetupError(),
                schedule.getSuspensionReason(),
                schedule.getCancelReason(),
                schedule.getVersion());
    }

    /** 판매자 회차 조회의 행으로 만든다. salesStatus 계산은 Entity로 만들 때와 같다. */
    public static SellerScheduleDetail of(SellerScheduleRow row, LocalDateTime now) {
        return new SellerScheduleDetail(
                row.id(),
                row.performanceId(),
                row.startAt(),
                row.bookingOpenAt(),
                row.bookingCloseAt(),
                row.operationStatus(),
                row.seatSetupStatus(),
                row.salesSuspended(),
                SalesStatus.of(
                        row.performanceStatus(),
                        row.operationStatus(),
                        row.bookingOpenAt(),
                        row.bookingCloseAt(),
                        row.salesSuspended(),
                        row.seatSetupStatus(),
                        now),
                row.canceledAt(),
                row.seatConfigVersion(),
                row.seatSetupRequestId(),
                row.seatSetupError(),
                row.suspensionReason(),
                row.cancelReason(),
                row.version());
    }
}
