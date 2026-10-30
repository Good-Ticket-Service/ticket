package com.sparta.ticket.performance.presentation.admin.dto;

import com.sparta.ticket.performance.application.dto.RegisteredSeats;
import java.util.List;

/** 물리 좌석 일괄 등록 응답(P5). createdSeatIds 길이는 createdCount와 같다. */
public record VenueSeatsResponse(int createdCount, List<Long> createdSeatIds, long venueVersion) {

    public static VenueSeatsResponse from(RegisteredSeats result) {
        return new VenueSeatsResponse(result.createdCount(), result.createdSeatIds(), result.venueVersion());
    }
}
