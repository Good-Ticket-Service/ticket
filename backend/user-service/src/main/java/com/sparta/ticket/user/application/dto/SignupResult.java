package com.sparta.ticket.user.application.dto;

import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.user.domain.User;
import java.util.UUID;

public record SignupResult(UUID userId, String username, Role role) {

    public static SignupResult from(User user) {
        return new SignupResult(user.getId(), user.getUsername(), user.getRole());
    }
}
