package com.sparta.ticket.reservation.queue.presentation;

import com.sparta.ticket.common.auth.CurrentUser;
import com.sparta.ticket.common.auth.UserContext;
import com.sparta.ticket.common.response.ApiResponse;
import com.sparta.ticket.reservation.queue.application.QueueService;
import com.sparta.ticket.reservation.queue.application.dto.QueueEntryResult;
import com.sparta.ticket.reservation.queue.application.dto.QueueStatusResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 대기열 API. 대기열은 예매 앞의 독립된 관문이라 공연 경로 아래가 아니라 자기 영역(/api/queues)에 둔다.
 * 줄을 서는 회원은 경로나 본문이 아니라 Gateway가 넘긴 사용자 헤더(@CurrentUser)로 정한다.
 * Gateway가 /api/queues/**를 reservation-service로 보낸다. 명세는 docs/api.md 대기열 절이다.
 */
@Tag(name = "대기열")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/queues")
public class QueueController {

    private final QueueService queueService;

    /** 공연 대기열에 줄을 선다. 본문 없음. 이미 서 있으면 새로 서지 않고 원래 순번을 돌려주므로 201이 아니라 200이다. */
    @Operation(summary = "대기열 진입")
    @PostMapping("/performances/{performanceId}")
    public ApiResponse<QueueEntryResult> enter(@PathVariable Long performanceId, @CurrentUser UserContext user) {
        return ApiResponse.ok(queueService.enter(performanceId, user.userId()));
    }

    /**
     * 내 순번을 확인한다(폴링). 응답이 캐시되면 요청이 서버에 오지 않아 이탈자로 잘못 판단하므로 no-store를 붙인다.
     * 조회 시각을 남기지만 서버의 관리 기록이라 GET으로 둔다.
     * 헤더를 붙여야 해서 다른 API와 달리 ResponseEntity로 돌려준다.
     */
    @Operation(summary = "대기열 순번 조회")
    @GetMapping("/performances/{performanceId}/me")
    public ResponseEntity<ApiResponse<QueueStatusResult>> findMyStatus(
            @PathVariable Long performanceId, @CurrentUser UserContext user) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(queueService.findMyStatus(performanceId, user.userId())));
    }

    /**
     * 대기를 취소한다. 204(본문 없음). 이미 줄에 없어도 204다.
     * 응답이 유실돼 앱이 다시 보낸 요청이 실패로 보이지 않도록, 여러 번 불러도 같은 결과(멱등)를 준다.
     */
    @Operation(summary = "대기 취소")
    @DeleteMapping("/performances/{performanceId}/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@PathVariable Long performanceId, @CurrentUser UserContext user) {
        queueService.leave(performanceId, user.userId());
    }
}
