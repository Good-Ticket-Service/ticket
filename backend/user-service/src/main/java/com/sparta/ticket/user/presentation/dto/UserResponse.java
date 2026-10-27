package com.sparta.ticket.user.presentation.dto;

import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.user.application.dto.UserResult;
import com.sparta.ticket.user.domain.UserStatus;
import java.time.LocalDateTime;

public record UserResponse(
        Long userId,
        String email,
        String name,
        String phone,
        Role role,
        UserStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static UserResponse from(UserResult result) {
        return new UserResponse(
                result.userId(),
                result.email(),
                result.name(),
                result.phone(),
                result.role(),
                result.status(),
                result.createdAt(),
                result.updatedAt());
    }
}
