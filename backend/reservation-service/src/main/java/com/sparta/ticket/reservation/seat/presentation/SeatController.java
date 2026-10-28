package com.sparta.ticket.reservation.seat.presentation;

import com.sparta.ticket.common.response.ApiResponse;
import com.sparta.ticket.reservation.seat.application.SelectableSeatQueryService;
import com.sparta.ticket.reservation.seat.presentation.dto.SelectableSeatsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 회차 좌석 API. JWT와 대기열 입장 토큰(X-Queue-Token)은 Gateway가 검사한다. */
@Tag(name = "좌석")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/seats/schedules/{scheduleId}")
public class SeatController {

    private final SelectableSeatQueryService selectableSeatQueryService;

    @Operation(summary = "선택 가능 좌석 조회", description = "좌석 배치도에 회차 좌석과 선택 가능 여부를 보여 준다. 조회 결과가 선점 성공을 보장하지는 않는다.")
    @GetMapping
    public ApiResponse<SelectableSeatsResponse> getSelectableSeats(@PathVariable Long scheduleId) {
        return ApiResponse.ok(SelectableSeatsResponse.from(selectableSeatQueryService.getSelectableSeats(scheduleId)));
    }
}
