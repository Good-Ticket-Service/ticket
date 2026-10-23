package com.sparta.ticket.user.application.dto;

import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.user.domain.User;
import java.time.LocalDateTime;
import java.util.UUID;

/** 사용자 정보. 비밀번호 해시는 담지 않는다. */
public record UserResult(
        UUID userId, String username, String name, Role role, LocalDateTime createdAt, LocalDateTime updatedAt) {

    public static UserResult from(User user) {
        return new UserResult(
                user.getId(),
                user.getUsername(),
                user.getName(),
                user.getRole(),
                user.getCreatedAt(),
                user.getUpdatedAt());
    }
}
