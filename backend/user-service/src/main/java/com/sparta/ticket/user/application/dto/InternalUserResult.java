package com.sparta.ticket.user.application.dto;

import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.user.domain.User;

/** 내부 사용자 조회 응답. Gateway 권한 재확인(UserSnapshot)과 다른 서비스의 사용자 확인에 쓴다. */
public record InternalUserResult(Long userId, String email, Role role) {

    public static InternalUserResult from(User user) {
        return new InternalUserResult(user.getId(), user.getEmail(), user.getRole());
    }
}
