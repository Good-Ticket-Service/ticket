package com.sparta.ticket.reservation.seat.application.setup;

/**
 * 좌석 준비 결과. 같은 구성 버전의 재요청에도 저장된 좌석으로 다시 센 같은 값을 준다.
 *
 * @param requestId 이번 요청의 값 그대로. 공연이 결과가 현재 요청의 것인지 확인하는 데 쓴다
 * @param configVersion 반영된 구성 버전
 */
public record SeatSetupResult(
        String requestId, Long scheduleId, long configVersion, int seatCount, int sellableCount) {}
