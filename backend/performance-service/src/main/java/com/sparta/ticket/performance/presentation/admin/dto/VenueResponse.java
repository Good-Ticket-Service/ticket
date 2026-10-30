package com.sparta.ticket.performance.presentation.admin.dto;

import com.sparta.ticket.performance.application.dto.VenueDetail;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** 공연장 응답(P1). 시각은 +09:00을 붙여 내보낸다. */
public record VenueResponse(
        Long id,
        String name,
        String address,
        String region,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    public static VenueResponse from(VenueDetail venue) {
        return new VenueResponse(
                venue.id(),
                venue.name(),
                venue.address(),
                venue.region(),
                venue.version(),
                offset(venue.createdAt()),
                offset(venue.updatedAt()));
    }

    private static OffsetDateTime offset(LocalDateTime time) {
        return time == null ? null : time.atZone(SEOUL).toOffsetDateTime();
    }
}
