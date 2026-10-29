package com.sparta.ticket.performance.presentation.dto;

import com.sparta.ticket.performance.application.dto.PublicSchedule;
import java.util.List;

/** 공개 회차 목록 응답의 data. 회차가 없으면 schedules는 빈 배열이다. */
public record PublicScheduleListResponse(Long performanceId, List<PublicScheduleResponse> schedules) {

    public static PublicScheduleListResponse of(Long performanceId, List<PublicSchedule> schedules) {
        return new PublicScheduleListResponse(
                performanceId,
                schedules.stream().map(PublicScheduleResponse::from).toList());
    }
}
