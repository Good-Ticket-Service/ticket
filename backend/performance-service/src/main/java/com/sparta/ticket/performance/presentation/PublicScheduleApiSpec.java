package com.sparta.ticket.performance.presentation;

import com.sparta.ticket.common.response.ApiResponse;
import com.sparta.ticket.performance.presentation.dto.PublicScheduleListResponse;
import com.sparta.ticket.performance.presentation.dto.PublicScheduleResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 공개 회차 조회 API의 계약. Swagger 문서와 파라미터 바인딩을 둔다. 경로와 HTTP 메서드는 구현 클래스 {@link PublicScheduleApi}에 둔다.
 * 명세는 .local/performance/api.md 「공개 조회」다.
 */
@Tag(name = "회차 공개 조회")
public interface PublicScheduleApiSpec {

    @Operation(
            summary = "공개 회차 목록 조회",
            description = "로그인 없이 호출한다. 공연의 회차를 시작 시각 순으로 준다. 취소 회차는 넣고 삭제 회차는 뺀다. "
                    + "판매 상태(salesStatus)는 서버 시각으로 계산한다. "
                    + "초안·삭제 공연과 없는 공연은 404 PERFORMANCE_NOT_FOUND다.")
    ApiResponse<PublicScheduleListResponse> getSchedules(
            @Parameter(description = "공연 ID") @PathVariable Long performanceId);

    @Operation(
            summary = "공개 회차 상세 조회",
            description = "로그인 없이 호출한다. 판매 상태(salesStatus)는 서버 시각으로 계산한다. "
                    + "회차가 없거나 삭제됐거나, 부모 공연이 초안·삭제면 404 SCHEDULE_NOT_FOUND다.")
    ApiResponse<PublicScheduleResponse> getSchedule(@Parameter(description = "회차 ID") @PathVariable Long scheduleId);
}
