package com.sparta.ticket.reservation.seat.presentation.internal.dto;

import com.sparta.ticket.reservation.seat.application.setup.SeatSetupResult;

/**
 * 회차 좌석 준비 응답. 내부 API라 ApiResponse로 감싸지 않는다.
 *
 * @param requestId 이번 요청의 값 그대로
 * @param seatCount 회차 좌석 수
 * @param sellableCount 그중 판매 대상 좌석 수
 */
public record SeatSetupResponse(
        String requestId, Long scheduleId, long configVersion, int seatCount, int sellableCount) {

    public static SeatSetupResponse from(SeatSetupResult result) {
        return new SeatSetupResponse(
                result.requestId(),
                result.scheduleId(),
                result.configVersion(),
                result.seatCount(),
                result.sellableCount());
    }
}
