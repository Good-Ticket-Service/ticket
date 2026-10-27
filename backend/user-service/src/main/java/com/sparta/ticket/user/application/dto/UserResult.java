package com.sparta.ticket.user.application.dto;

import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.user.domain.User;
import com.sparta.ticket.user.domain.UserStatus;
import java.time.LocalDateTime;

/** 사용자 정보. 비밀번호 해시는 담지 않는다. */
public record UserResult(
        Long userId,
        String email,
        String name,
        String phone,
        Role role,
        UserStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static UserResult from(User user) {
        return new UserResult(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getPhone(),
                user.getRole(),
                user.getStatus(),
                user.getCreatedAt(),
                user.getUpdatedAt());
    }
}
