package com.sparta.ticket.performance.application.dto;

import com.sparta.ticket.performance.domain.venue.Venue;
import java.time.LocalDateTime;

/** 공연장 등록 결과(P1). 시각은 KST LocalDateTime이며 응답 변환에서 +09:00을 붙인다. */
public record VenueDetail(
        Long id,
        String name,
        String address,
        String region,
        long version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static VenueDetail from(Venue venue) {
        return new VenueDetail(
                venue.getId(),
                venue.getName(),
                venue.getAddress(),
                venue.getRegion(),
                venue.getVersion(),
                venue.getCreatedAt(),
                venue.getUpdatedAt());
    }
}
