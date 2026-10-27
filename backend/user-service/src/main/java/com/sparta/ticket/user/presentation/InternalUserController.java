package com.sparta.ticket.user.presentation;

import com.sparta.ticket.user.application.UserQueryService;
import com.sparta.ticket.user.application.dto.InternalUserResult;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서비스 간 호출 전용. Gateway에 라우팅이 없고 /internal/** 을 404로 막아 외부에서 호출할 수 없다.
 * Note: 내부 API는 서비스끼리의 계약이라 DTO를 ApiResponse로 감싸지 않고 그대로 반환한다. 실패는 외부 API와 같은
 * 공통 에러 형식이라 호출하는 쪽 FeignErrorDecoder가 code를 그대로 읽는다.
 */
@RestController
@Tag(name = "내부 사용자 API")
@RequiredArgsConstructor
@RequestMapping("/internal/users")
public class InternalUserController {

    private final UserQueryService userQueryService;

    /** Gateway가 토큰을 검증한 뒤 저장된 role·탈퇴 여부를 다시 확인할 때 부른다(60초 캐시). */
    @GetMapping("/{userId}")
    public InternalUserResult getUser(@PathVariable Long userId) {
        return userQueryService.getInternalUser(userId);
    }
}
