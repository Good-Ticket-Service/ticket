package com.sparta.ticket.reservation.seat.presentation;

import com.sparta.ticket.common.auth.CurrentUser;
import com.sparta.ticket.common.auth.UserContext;
import com.sparta.ticket.common.response.ApiResponse;
import com.sparta.ticket.reservation.seat.application.SelectableSeatSnapshotService;
import com.sparta.ticket.reservation.seat.application.dto.SeatMapSnapshot;
import com.sparta.ticket.reservation.seat.application.hold.SeatHoldResult;
import com.sparta.ticket.reservation.seat.application.hold.SeatHoldService;
import com.sparta.ticket.reservation.seat.application.release.SeatReleaseService;
import com.sparta.ticket.reservation.seat.application.remaining.RemainingSeatService;
import com.sparta.ticket.reservation.seat.presentation.dto.RemainingSeatsResponse;
import com.sparta.ticket.reservation.seat.presentation.dto.SeatHoldRequest;
import com.sparta.ticket.reservation.seat.presentation.dto.SeatHoldResponse;
import com.sparta.ticket.reservation.seat.presentation.dto.SeatLayoutResponse;
import com.sparta.ticket.reservation.seat.presentation.dto.SeatStatusResponse;
import com.sparta.ticket.reservation.seat.presentation.dto.SelectableSeatsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
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
import org.springframework.web.context.request.WebRequest;

/** 회차 좌석 API. JWT와 대기열 입장 토큰(X-Queue-Token)은 Gateway가 검사한다. */
@Tag(name = "좌석")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/seats/schedules/{scheduleId}")
public class SeatController {

    private final SelectableSeatSnapshotService selectableSeatSnapshotService;
    private final SeatHoldService seatHoldService;
    private final SeatReleaseService seatReleaseService;
    private final RemainingSeatService remainingSeatService;

    /**
     * layout은 브라우저에만 저장하고(private) 쓸 때마다 ETag로 다시 확인한다(no-cache). Cache-Control이 없으면 Gateway 보안 체인이
     * no-store를 붙여 저장되지 않고, 그러면 If-None-Match가 오지 않아 304가 생기지 않는다.
     */
    private static final CacheControl LAYOUT_CACHE = CacheControl.noCache().cachePrivate();

    /**
     * 등급별로 남은 좌석 수를 준다. 로그인·입장 토큰 없이 부르는 공개 API다(Gateway whitelist).
     * 판매 대상 좌석 중 AVAILABLE만 세고, 몇 초 동안 캐시한 값일 수 있다. 실제로 선점할 수 있는지는 좌석 조회·선점이 다시 확인한다.
     */
    @Operation(
            summary = "등급별 잔여석 조회",
            description = "판매 대상 좌석 중 비어 있는 좌석 수를 등급별로 준다. 화면 안내용이라 몇 초 늦을 수 있다(cached·asOf로 알린다).")
    @GetMapping("/remaining")
    public ApiResponse<RemainingSeatsResponse> getRemaining(@PathVariable Long scheduleId) {
        return ApiResponse.ok(RemainingSeatsResponse.from(remainingSeatService.getRemaining(scheduleId)));
    }

    /** 회차별로 짧게(설정값, 1초) 캐시한 스냅샷일 수 있다. asOf는 스냅샷을 만든 시각이다. 실제로 선점할 수 있는지는 선점 API가 다시 판정한다. */
    @Operation(
            summary = "선택 가능 좌석 조회",
            description = "좌석 배치도에 회차 좌석과 선택 가능 여부를 보여 준다. 1초 안의 스냅샷일 수 있고(asOf), 조회 결과가 선점 성공을 보장하지는 않는다.")
    @GetMapping
    public ApiResponse<SelectableSeatsResponse> getSelectableSeats(@PathVariable Long scheduleId) {
        return ApiResponse.ok(
                SelectableSeatsResponse.from(selectableSeatSnapshotService.getSelectableSeats(scheduleId)));
    }

    /**
     * 회차 좌석 배치(선택 가능 여부 제외). ETag는 configVersion이고, If-None-Match가 같으면 본문 없이 304다.
     * 304 판단은 캐시한 스냅샷의 configVersion만 보고 하며 응답 본문은 만들지 않는다. 좌석 위치·등급·가격은 좌석 준비로만 바뀌고,
     * 좌석 준비는 바꾼 좌석에 새 configVersion을 쓴다. 그래서 configVersion이 같으면 layout도 같다.
     */
    @Operation(
            summary = "좌석 배치 조회",
            description = "회차 좌석 배치를 준다. 선택 가능 여부는 status로 따로 받는다. ETag(configVersion)가 같으면 304다.")
    @GetMapping("/layout")
    public ResponseEntity<ApiResponse<SeatLayoutResponse>> getLayout(
            @PathVariable Long scheduleId, WebRequest request) {
        SeatMapSnapshot snapshot = selectableSeatSnapshotService.getSnapshot(scheduleId);
        // ETag 헤더는 checkNotModified가 200·304 모두 응답에 쓴다. ResponseEntity에 또 넣으면 304에서 두 줄이 된다
        if (request.checkNotModified("\"" + snapshot.configVersion() + "\"")) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .cacheControl(LAYOUT_CACHE)
                    .build();
        }
        return ResponseEntity.ok().cacheControl(LAYOUT_CACHE).body(ApiResponse.ok(SeatLayoutResponse.from(snapshot)));
    }

    /**
     * 회차 좌석의 선택 가능 비트맵. layout과 같은 스냅샷에서 만든다. 1초마다 바뀌므로 ETag를 두지 않고, 지난 상태가 브라우저·프록시에
     * 남지 않게 no-store를 직접 붙인다(Gateway 기본 헤더에 기대지 않는다).
     */
    @Operation(
            summary = "좌석 선택 가능 상태 조회",
            description = "layout 좌석 순서대로 선택 가능 여부를 비트맵(base64)으로 준다. 1초 안의 스냅샷일 수 있다(asOf).")
    @GetMapping("/status")
    public ResponseEntity<ApiResponse<SeatStatusResponse>> getStatus(@PathVariable Long scheduleId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(SeatStatusResponse.from(selectableSeatSnapshotService.getSnapshot(scheduleId))));
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
