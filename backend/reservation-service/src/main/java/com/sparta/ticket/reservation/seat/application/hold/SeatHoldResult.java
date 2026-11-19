package com.sparta.ticket.reservation.seat.application.hold;

import java.time.LocalDateTime;

/**
 * 좌석 선점 결과. created가 true면 새로 만든 선점(201), false면 같은 회원·같은 좌석의 유효한 선점을 그대로 돌려준 것(200)이다.
 * holdExpiresAt은 좌석과 예약에 같은 값으로 저장된 선점 만료 시각(KST)이고, basePrice는 선점 시점의 좌석 가격이다.
 */
public record SeatHoldResult(
        Long reservationId, Long scheduleSeatId, LocalDateTime holdExpiresAt, Integer basePrice, boolean created) {}
