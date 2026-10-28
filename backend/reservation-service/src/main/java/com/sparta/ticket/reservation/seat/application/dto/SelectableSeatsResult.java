package com.sparta.ticket.reservation.seat.application.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 회차의 선택 가능 좌석 조회 결과. asOf는 판정에 쓴 공용 Clock의 시각(KST)이다. */
public record SelectableSeatsResult(
        Long scheduleId, long configVersion, LocalDateTime asOf, List<SelectableSeatResult> seats) {}
