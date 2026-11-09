package com.sparta.ticket.waitingroom.presentation;

import com.sparta.ticket.common.auth.RequireRole;
import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.common.response.ApiResponse;
import com.sparta.ticket.waitingroom.application.WaitingRoomService;
import com.sparta.ticket.waitingroom.application.dto.WaitingRoomResult;
import com.sparta.ticket.waitingroom.presentation.dto.CreateWaitingRoomRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 대기열(room) API. 관리자의 생성과 회원의 scope 조회를 받는다.
 * 요청 값 검증(@Valid)과 역할 확인(@RequireRole)만 하고 처리는 WaitingRoomService에 넘긴다. 명세는 docs/api.md 대기열 절이다.
 */
@Tag(name = "대기열")
@RestController
@RequiredArgsConstructor
public class WaitingRoomController {

    private final WaitingRoomService waitingRoomService;

    /** 대기열 생성. 201과 만든 room을 돌려준다. 생성 뒤에는 바꿀 수 없고 closeAt이 지나면 저절로 끝난다. */
    @Operation(summary = "대기열 생성")
    @RequireRole(Role.MASTER)
    @PostMapping("/api/admin/waiting-rooms")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<WaitingRoomResult> create(@Valid @RequestBody CreateWaitingRoomRequest request) {
        return ApiResponse.created(
                "대기열이 생성되었습니다.",
                waitingRoomService.create(
                        request.scopes(),
                        request.openAt().toInstant(),
                        request.closeAt().toInstant()));
    }

    /**
     * scope로 대기열을 찾는다. 프런트가 회차 화면에서 대기열이 있는지와 roomId를 알 때 쓴다.
     * 끝난 대기열은 찾지 않는다(404 WAITING_ROOM_NOT_FOUND → 대기열 없이 좌석 화면으로).
     */
    @Operation(summary = "대기열 조회(scope)")
    @GetMapping("/api/waiting-rooms")
    public ApiResponse<WaitingRoomResult> findByScope(@RequestParam String scope) {
        return ApiResponse.ok(waitingRoomService.findByScope(scope));
    }
}
