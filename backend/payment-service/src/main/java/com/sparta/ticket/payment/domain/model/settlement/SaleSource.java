package com.sparta.ticket.payment.domain.model.settlement;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 정산이 결제에서 받는 승인 스냅샷. settlement는 payment Entity를 직접 보지 않고 이 값만 쓴다.
 *
 * @param scheduleStartAt 회차 시작 시각(KST). 계약 적용 전 결제는 없을 수 있다
 */
public record SaleSource(
        Long paymentId,
        Long sellerId,
        Long performanceId,
        Long scheduleId,
        LocalDateTime scheduleStartAt,
        LocalDateTime approvedAt,
        int gross,
        int discount,
        int bookingFee,
        int paid) {

    public SaleSource {
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(sellerId, "sellerId");
        Objects.requireNonNull(performanceId, "performanceId");
        Objects.requireNonNull(scheduleId, "scheduleId");
        Objects.requireNonNull(approvedAt, "approvedAt");
    }
}
