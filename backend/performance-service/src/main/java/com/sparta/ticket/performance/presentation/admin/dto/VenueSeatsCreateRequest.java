package com.sparta.ticket.performance.presentation.admin.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 물리 좌석 일괄 등록 요청. 위치 중복(409)과 등급 소속(400)은 서비스에서 본다.
 * Note: 일괄 개수 상한은 아직 없다. 나중에 정한다(.local/performance/plan.md 6절).
 */
public record VenueSeatsCreateRequest(@NotEmpty List<@NotNull @Valid Seat> seats) {

    public record Seat(
            @NotBlank @Size(max = 20) String section,
            @NotBlank @Size(max = 10) String rowLabel,
            @NotNull @Min(1) Integer seatNumber,
            @NotNull @Positive Long venueSeatGradeId) {}
}
