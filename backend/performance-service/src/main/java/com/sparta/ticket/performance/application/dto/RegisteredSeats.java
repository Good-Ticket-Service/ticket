package com.sparta.ticket.performance.application.dto;

import java.util.List;

/** 좌석 일괄 등록 결과. ID는 요청 순서대로다. */
public record RegisteredSeats(List<Long> createdSeatIds, long venueVersion) {

    /** 명세상 createdSeatIds 길이와 같아야 하므로 따로 받지 않고 계산한다. */
    public int createdCount() {
        return createdSeatIds.size();
    }
}
