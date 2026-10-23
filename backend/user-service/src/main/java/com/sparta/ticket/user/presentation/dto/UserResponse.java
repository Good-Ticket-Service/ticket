package com.sparta.ticket.user.presentation.dto;

import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.user.application.dto.UserResult;
import java.time.LocalDateTime;
import java.util.UUID;

public record UserResponse(
        UUID userId,
        String username,
        String name,
        Role role,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static UserResponse from(UserResult result) {
        return new UserResponse(result.userId(), result.username(), result.name(), result.role(),
                result.createdAt(), result.updatedAt());
    }
}
