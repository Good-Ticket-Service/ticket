package com.sparta.ticket.performance.presentation;

import com.sparta.ticket.common.response.ApiResponse;
import com.sparta.ticket.performance.application.PublicScheduleService;
import com.sparta.ticket.performance.presentation.dto.PublicScheduleListResponse;
import com.sparta.ticket.performance.presentation.dto.PublicScheduleResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 일반 사용자용 회차 조회 API. 로그인 없이 누구나 호출한다. 경로·HTTP 메서드만 여기 두고, Swagger 문서와 파라미터 바인딩은 {@link PublicScheduleApiSpec}에 있다.
 * Note: 목록은 공연 아래(/performances/{id}/schedules), 상세는 회차 단독 경로(/schedules/{id})라 공통 prefix인 /api만 클래스에 둔다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class PublicScheduleApi implements PublicScheduleApiSpec {

    private final PublicScheduleService publicScheduleService;

    /** 공개 공연의 회차 목록. 취소 회차도 판매 상태 CANCELED로 넣는다. */
    @Override
    @GetMapping("/performances/{performanceId}/schedules")
    public ApiResponse<PublicScheduleListResponse> getSchedules(Long performanceId) {
        return ApiResponse.ok(
                PublicScheduleListResponse.of(performanceId, publicScheduleService.getSchedules(performanceId)));
    }

    /** 공개 공연의 회차 상세. */
    @Override
    @GetMapping("/schedules/{scheduleId}")
    public ApiResponse<PublicScheduleResponse> getSchedule(Long scheduleId) {
        return ApiResponse.ok(PublicScheduleResponse.from(publicScheduleService.getSchedule(scheduleId)));
    }
}
