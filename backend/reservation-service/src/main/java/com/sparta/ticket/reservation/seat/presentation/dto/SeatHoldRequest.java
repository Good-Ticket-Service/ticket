package com.sparta.ticket.reservation.seat.presentation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 좌석 선점 요청. 좌석 하나만 받는다(회원·회차당 1장).
 * 등급·가격·만료 시각은 받지 않는다. 가격은 좌석 스냅샷, 만료 시각은 서버 시계로 정한다.
 */
public record SeatHoldRequest(@NotNull @Positive Long scheduleSeatId) {}
