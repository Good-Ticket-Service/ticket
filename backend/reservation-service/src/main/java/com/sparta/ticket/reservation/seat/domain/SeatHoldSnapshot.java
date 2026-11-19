package com.sparta.ticket.reservation.seat.domain;

import java.time.LocalDateTime;

/**
 * 선점 진입 단계에서 필요한 좌석 상태만 읽은 값. 잠금 없는 사전 판정이며 최종 판정은 선점 트랜잭션이 한다.
 * 고정 정보만 담은 값({@link #fixed})은 점유(status·reservationId·holdExpiresAt)가 null이다. Redis 점유 판정(#449 후속)이 쓴다.
 */
public record SeatHoldSnapshot(
        Long scheduleSeatId,
        Long venueSeatId,
        long configVersion,
        boolean sellable,
        Integer basePrice,
        SeatStatus status,
        Long reservationId,
        LocalDateTime holdExpiresAt) {

    /** 판매 중에 바뀌지 않는 정보만 담는다(architecture 10절: 공개 후 배치·가격·제외 불변). 점유는 모른다. */
    public static SeatHoldSnapshot fixed(
            Long scheduleSeatId, Long venueSeatId, long configVersion, boolean sellable, Integer basePrice) {
        return new SeatHoldSnapshot(scheduleSeatId, venueSeatId, configVersion, sellable, basePrice, null, null, null);
    }

    /** DB에서 점유까지 읽은 값인지. 고정 정보만 담은 값이면 false다. */
    public boolean occupancyKnown() {
        return status != null;
    }

    /** HOLD 만료 여부. 좌석 테이블 제약상 HOLD에는 만료 시각이 있다. */
    public boolean isHoldExpiredAt(LocalDateTime now) {
        return status == SeatStatus.HOLD && !holdExpiresAt.isAfter(now);
    }
}
