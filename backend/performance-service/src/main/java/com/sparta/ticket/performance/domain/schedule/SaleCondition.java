package com.sparta.ticket.performance.domain.schedule;

import com.sparta.ticket.performance.domain.performance.OperationStatus;
import com.sparta.ticket.performance.domain.performance.PublicationStatus;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 회차 하나를 지금 팔 수 있는지 판단하는 데 필요한 공연·회차 상태. 좌석별 판단(SEAT_NOT_SELLABLE)은 여기서 하지 않는다.
 *
 * @param seatConfigVersion 회차의 최신 좌석 구성 버전. 좌석 서비스가 가진 스냅샷 버전과 비교한다.
 */
public record SaleCondition(
        PublicationStatus publicationStatus,
        OperationStatus performanceStatus,
        OperationStatus scheduleStatus,
        LocalDateTime bookingOpenAt,
        LocalDateTime bookingCloseAt,
        boolean salesSuspended,
        SeatSetupStatus seatSetupStatus,
        long seatConfigVersion) {

    /**
     * 아래 순서대로 먼저 맞는 거절 사유를 준다. 모두 통과하면 empty다.
     * 공연 미공개 → 공연 취소 → 회차 취소 → 마감 → 오픈 전 → 판매 중지 → 좌석 미준비 → 구성 버전 불일치.
     * 기간(마감·오픈 전)을 판매 중지·좌석 미준비보다 먼저 보는 것은 공개 조회의 판매 상태(CLOSED·BEFORE_OPEN이 UNAVAILABLE보다 먼저)와 맞추기 위해서다.
     * 예매 마감 시각과 같으면 마감, 예매 오픈 시각과 같으면 열린 것으로 본다.
     *
     * @param requestedConfigVersion 좌석 서비스가 보낸 좌석 구성 버전
     */
    public Optional<SaleRejectReason> rejectReason(LocalDateTime now, long requestedConfigVersion) {
        if (publicationStatus != PublicationStatus.PUBLISHED) {
            return Optional.of(SaleRejectReason.PERFORMANCE_NOT_PUBLISHED);
        }
        if (performanceStatus == OperationStatus.CANCELED) {
            return Optional.of(SaleRejectReason.PERFORMANCE_CANCELED);
        }
        if (scheduleStatus == OperationStatus.CANCELED) {
            return Optional.of(SaleRejectReason.SCHEDULE_CANCELED);
        }
        if (!now.isBefore(bookingCloseAt)) {
            return Optional.of(SaleRejectReason.SALES_CLOSED);
        }
        if (now.isBefore(bookingOpenAt)) {
            return Optional.of(SaleRejectReason.BEFORE_OPEN);
        }
        if (salesSuspended) {
            return Optional.of(SaleRejectReason.SALES_SUSPENDED);
        }
        if (seatSetupStatus != SeatSetupStatus.READY) {
            return Optional.of(SaleRejectReason.SEAT_SETUP_NOT_READY);
        }
        if (requestedConfigVersion != seatConfigVersion) {
            return Optional.of(SaleRejectReason.SEAT_CONFIG_VERSION_MISMATCH);
        }
        return Optional.empty();
    }
}
