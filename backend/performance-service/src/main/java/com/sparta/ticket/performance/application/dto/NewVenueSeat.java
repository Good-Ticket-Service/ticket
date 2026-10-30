package com.sparta.ticket.performance.application.dto;

import com.sparta.ticket.performance.domain.venue.SeatPosition;

/** 좌석 일괄 등록의 한 건. 공백 정리·형식 검증은 요청 DTO에서 끝난 값이다. */
public record NewVenueSeat(String section, String rowLabel, int seatNumber, Long venueSeatGradeId) {

    public SeatPosition position() {
        return new SeatPosition(section, rowLabel, seatNumber);
    }
}
