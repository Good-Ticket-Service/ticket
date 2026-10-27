package com.sparta.ticket.user.presentation;

import com.sparta.ticket.common.auth.CurrentUser;
import com.sparta.ticket.common.auth.UserContext;
import com.sparta.ticket.common.response.ApiResponse;
import com.sparta.ticket.user.application.UserCommandService;
import com.sparta.ticket.user.application.UserQueryService;
import com.sparta.ticket.user.presentation.dto.UpdateMyInfoRequest;
import com.sparta.ticket.user.presentation.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "사용자")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/users")
public class UserController {

    private final UserQueryService userQueryService;
    private final UserCommandService userCommandService;

    @Operation(summary = "내 정보 조회")
    @GetMapping("/me")
    public ApiResponse<UserResponse> getMyInfo(@CurrentUser UserContext user) {
        return ApiResponse.ok(UserResponse.from(userQueryService.getMyInfo(user)));
    }

    @Operation(summary = "내 정보 수정", description = "바꿀 필드만 보낸다. 빠졌거나 null인 필드는 그대로 둔다.")
    @PatchMapping("/me")
    public ApiResponse<UserResponse> updateMyInfo(
            @CurrentUser UserContext user, @Valid @RequestBody UpdateMyInfoRequest request) {
        return ApiResponse.ok(UserResponse.from(userCommandService.updateMyInfo(user, request.toCommand())));
    }
}
