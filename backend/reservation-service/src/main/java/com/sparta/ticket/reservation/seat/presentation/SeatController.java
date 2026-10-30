package com.sparta.ticket.reservation.seat.presentation;

import com.sparta.ticket.common.auth.CurrentUser;
import com.sparta.ticket.common.auth.UserContext;
import com.sparta.ticket.common.response.ApiResponse;
import com.sparta.ticket.reservation.seat.application.SelectableSeatQueryService;
import com.sparta.ticket.reservation.seat.application.hold.SeatHoldResult;
import com.sparta.ticket.reservation.seat.application.hold.SeatHoldService;
import com.sparta.ticket.reservation.seat.application.release.SeatReleaseService;
import com.sparta.ticket.reservation.seat.presentation.dto.SeatHoldRequest;
import com.sparta.ticket.reservation.seat.presentation.dto.SeatHoldResponse;
import com.sparta.ticket.reservation.seat.presentation.dto.SelectableSeatsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 회차 좌석 API. JWT와 대기열 입장 토큰(X-Queue-Token)은 Gateway가 검사한다. */
@Tag(name = "좌석")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/seats/schedules/{scheduleId}")
public class SeatController {

    private final SelectableSeatQueryService selectableSeatQueryService;
    private final SeatHoldService seatHoldService;
    private final SeatReleaseService seatReleaseService;

    @Operation(summary = "선택 가능 좌석 조회", description = "좌석 배치도에 회차 좌석과 선택 가능 여부를 보여 준다. 조회 결과가 선점 성공을 보장하지는 않는다.")
    @GetMapping
    public ApiResponse<SelectableSeatsResponse> getSelectableSeats(@PathVariable Long scheduleId) {
        return ApiResponse.ok(SelectableSeatsResponse.from(selectableSeatQueryService.getSelectableSeats(scheduleId)));
    }

    /**
     * 좌석 1장을 10분 동안 선점하고 예약을 HOLD로 만든다. 새로 만들면 201, 같은 회원·같은 좌석의 유효한 선점이 이미 있으면
     * 그 예약을 200으로 돌려준다. 응답 본문 형식은 같고 ApiResponse의 status만 다르다. 상태 코드가 결과에 따라 달라 ResponseEntity로 돌려준다.
     */
    @Operation(
            summary = "좌석 선점",
            description = "좌석 1장을 10분 동안 선점하고 예약을 HOLD로 만든다. 같은 좌석의 유효한 선점을 다시 요청하면 기존 예약을 200으로 준다.")
    @PostMapping("/holds")
    public ResponseEntity<ApiResponse<SeatHoldResponse>> hold(
            @CurrentUser UserContext user, @PathVariable Long scheduleId, @Valid @RequestBody SeatHoldRequest request) {
        SeatHoldResult result = seatHoldService.hold(user.userId(), scheduleId, request.scheduleSeatId());
        SeatHoldResponse body = SeatHoldResponse.from(result);
        if (result.created()) {
            return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created("좌석을 선점했습니다.", body));
        }
        return ResponseEntity.ok(ApiResponse.ok(body));
    }

    /**
     * 결제 전 사용자가 선점한 좌석을 놓는다. 이미 풀렸거나 만료된 선점도 204라 같은 요청을 반복해도 된다.
     * 결제 중·확정·취소된 예약이면 409 INVALID_STATUS_TRANSITION이다. 입장 토큰은 요구하지 않는다.
     */
    @Operation(summary = "선점 해제", description = "결제 전 선점한 좌석을 놓고 예약을 RELEASED로 만든다. 이미 끝난 선점도 204다.")
    @DeleteMapping("/holds/{reservationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(
            @CurrentUser UserContext user, @PathVariable Long scheduleId, @PathVariable Long reservationId) {
        seatReleaseService.release(user.userId(), scheduleId, reservationId);
    }
}
