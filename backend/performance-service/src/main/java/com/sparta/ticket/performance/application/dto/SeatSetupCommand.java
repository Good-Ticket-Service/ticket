package com.sparta.ticket.performance.application.dto;

import java.util.List;

/**
 * 좌석 서비스에 보내는 회차 좌석 준비 요청(docs/api.md 「좌석 내부 API」). seats는 회차에 만들 좌석 전체다.
 * 같은 구성의 재전송은 같은 requestId를 쓴다. 결과를 반영할 때 회차의 현재 값과 비교하는 기준이기도 하다.
 */
public record SeatSetupCommand(String requestId, Long scheduleId, long configVersion, List<Line> seats) {

    /** basePrice는 판매 제외 좌석이면서 그 등급에 가격이 없을 때만 null이다. */
    public record Line(
            Long venueSeatId,
            Long venueSeatGradeId,
            String gradeName,
            Integer basePrice,
            boolean sellable,
            String section,
            String rowLabel,
            int seatNumber) {}
}
