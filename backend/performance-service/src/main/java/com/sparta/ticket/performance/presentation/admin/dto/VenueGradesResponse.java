package com.sparta.ticket.performance.presentation.admin.dto;

import com.sparta.ticket.performance.application.dto.RegisteredGrades;
import java.util.List;

/** 좌석 등급 일괄 등록 응답(P3). */
public record VenueGradesResponse(List<Grade> grades, long venueVersion) {

    public record Grade(Long id, String name) {}

    public static VenueGradesResponse from(RegisteredGrades result) {
        return new VenueGradesResponse(
                result.grades().stream().map(g -> new Grade(g.id(), g.name())).toList(), result.venueVersion());
    }
}
