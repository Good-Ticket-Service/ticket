package com.sparta.ticket.performance.application.dto;

import com.sparta.ticket.performance.domain.schedule.SaleRejectReason;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 판매 검증 결과. 거절이어도 회차·공연 정보는 모두 채우고 seats만 비운다(예매가 거절 응답에서도 performanceId·sellerId를 읽는다).
 * 시각은 KST다.
 *
 * @param configVersion 회차의 최신 좌석 구성 버전. SEAT_CONFIG_VERSION_MISMATCH이면 좌석 서비스가 이 값으로 갱신할 수 있다.
 * @param policyVersion 부모 공연의 version
 */
public record SaleValidationResult(
        boolean allowed,
        SaleRejectReason reasonCode,
        Long scheduleId,
        Long performanceId,
        Long sellerId,
        long configVersion,
        long policyVersion,
        String performanceTitle,
        LocalDateTime scheduleStartAt,
        LocalDateTime validatedAt,
        List<Seat> seats) {

    public static SaleValidationResult allowed(SaleTarget target, LocalDateTime validatedAt, List<Seat> seats) {
        return of(target, null, validatedAt, seats);
    }

    public static SaleValidationResult rejected(SaleTarget target, SaleRejectReason reason, LocalDateTime validatedAt) {
        return of(target, reason, validatedAt, List.of());
    }

    private static SaleValidationResult of(
            SaleTarget target, SaleRejectReason reason, LocalDateTime validatedAt, List<Seat> seats) {
        return new SaleValidationResult(
                reason == null,
                reason,
                target.scheduleId(),
                target.performanceId(),
                target.sellerId(),
                target.seatConfigVersion(),
                target.performanceVersion(),
                target.performanceTitle(),
                target.startAt(),
                validatedAt,
                seats);
    }

    /** 허용한 좌석의 등급과 공연 공통 가격. */
    public record Seat(Long venueSeatId, Long venueSeatGradeId, String gradeName, int basePrice) {}
}
