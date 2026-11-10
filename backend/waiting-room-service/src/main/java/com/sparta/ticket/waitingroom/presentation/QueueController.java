package com.sparta.ticket.waitingroom.presentation;

import com.sparta.ticket.common.auth.CurrentUser;
import com.sparta.ticket.common.auth.UserContext;
import com.sparta.ticket.common.response.ApiResponse;
import com.sparta.ticket.waitingroom.application.QueueService;
import com.sparta.ticket.waitingroom.application.dto.QueueStatusResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 대기열 줄 서기 API. 줄은 room 단위이고, 줄에 선 기록(entry)을 room 아래 자원으로 둔다.
 * roomId만 있으면 로그인 회원 누구나 줄을 설 수 있다. 줄을 서는 회원은 경로나 본문이 아니라 Gateway가 넘긴 사용자 헤더(@CurrentUser)로 정한다.
 * 명세는 docs/api.md 대기열 절이다.
 */
@Tag(name = "대기열")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/waiting-rooms/{roomId}/entries")
public class QueueController {

    private final QueueService queueService;

    /**
     * 대기열에 줄을 선다. 본문 없음. 이미 서 있으면 새로 서지 않고 원래 순번을 돌려주므로 201이 아니라 200이다.
     * 이미 입장해 있으면 줄에 다시 세우지 않고 순번 조회와 같은 ENTERED 응답을 준다.
     */
    @Operation(summary = "대기열 진입")
    // Note: 응답은 JSON뿐이라 형식을 미리 정한다. 정하지 않으면 요청마다 등록된 변환기를 모두 훑어 쓸 수 있는 형식을 고른다
    // (11-10 JFR: 오픈 순간 몰림에서 응답 쓰기가 진입 CPU의 18%, 그중 형식 고르기가 큰 몫). 응답 내용·형식은 그대로다
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<QueueStatusResult> enter(@PathVariable Long roomId, @CurrentUser UserContext user) {
        return ApiResponse.ok(queueService.enter(roomId, user.userId()));
    }

    /**
     * 내 순번을 확인한다(폴링). 응답이 캐시되면 요청이 서버에 오지 않아 이탈자로 잘못 판단하므로 no-store를 붙인다.
     * 조회 시각을 남기지만 서버의 관리 기록이라 GET으로 둔다.
     * 헤더를 붙여야 해서 다른 API와 달리 ResponseEntity로 돌려준다.
     */
    @Operation(summary = "대기열 순번 조회")
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<QueueStatusResult>> findMyStatus(
            @PathVariable Long roomId, @CurrentUser UserContext user) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(queueService.findMyStatus(roomId, user.userId())));
    }

    /**
     * 대기를 취소한다. 204(본문 없음). 이미 줄에 없어도 204다.
     * 응답이 유실돼 앱이 다시 보낸 요청이 실패로 보이지 않도록, 여러 번 불러도 같은 결과(멱등)를 준다.
     */
    @Operation(summary = "대기 취소")
    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@PathVariable Long roomId, @CurrentUser UserContext user) {
        queueService.leave(roomId, user.userId());
    }
}
