package com.sparta.ticket.reservation.queue.presentation.internal;

import com.sparta.ticket.reservation.queue.application.WaitingRoomService;
import com.sparta.ticket.reservation.queue.presentation.internal.dto.ProtectedScopesResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gateway 전용 대기열 내부 API. Gateway 라우트가 /api/**만 받으므로 외부에서 호출할 수 없다.
 * Note: Gateway는 이 목록을 주기적으로(10초) 받아 캐시하고, 목록의 scope에 해당하는 요청만 입장 토큰을 검사한다.
 * 대기열에 요청마다 묻지 않기 위해서다. 오픈 전 room도 넘겨, 오픈 순간부터 다음 갱신까지 검사 없이 지나가는 틈을 없앤다.
 */
@Tag(name = "대기열 내부")
@RestController
@RequiredArgsConstructor
public class WaitingRoomInternalController {

    private final WaitingRoomService waitingRoomService;

    @Operation(summary = "보호 대상 목록", description = "끝나지 않은 room의 scope와 closeAt. 오픈 전 room도 포함한다.")
    @GetMapping("/internal/waiting-rooms/scopes")
    public ProtectedScopesResponse findProtectedScopes() {
        return ProtectedScopesResponse.from(waitingRoomService.findActive());
    }
}
