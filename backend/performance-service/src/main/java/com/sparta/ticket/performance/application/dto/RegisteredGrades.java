package com.sparta.ticket.performance.application.dto;

import com.sparta.ticket.performance.domain.venue.VenueSeatGrade;
import java.util.List;

/** 좌석 등급 일괄 등록 결과. 등급은 요청 순서대로다. */
public record RegisteredGrades(List<Grade> grades, long venueVersion) {

    public record Grade(Long id, String name) {}

    public static RegisteredGrades of(List<VenueSeatGrade> saved, long venueVersion) {
        return new RegisteredGrades(
                saved.stream().map(g -> new Grade(g.getId(), g.getName())).toList(), venueVersion);
    }
}
