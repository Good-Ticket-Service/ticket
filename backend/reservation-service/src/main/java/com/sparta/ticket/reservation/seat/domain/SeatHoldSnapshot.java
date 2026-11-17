package com.sparta.ticket.reservation.seat.domain;

import java.time.LocalDateTime;

/** 선점 진입 단계에서 필요한 좌석 상태만 읽은 값. 잠금 없는 사전 판정이며 최종 판정은 선점 트랜잭션이 한다. */
public record SeatHoldSnapshot(
        Long scheduleSeatId,
        Long venueSeatId,
        long configVersion,
        boolean sellable,
        Integer basePrice,
        SeatStatus status,
        Long reservationId,
        LocalDateTime holdExpiresAt) {

    /** HOLD 만료 여부. 좌석 테이블 제약상 HOLD에는 만료 시각이 있다. */
    public boolean isHoldExpiredAt(LocalDateTime now) {
        return status == SeatStatus.HOLD && !holdExpiresAt.isAfter(now);
    }
}
