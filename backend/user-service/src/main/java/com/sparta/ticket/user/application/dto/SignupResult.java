package com.sparta.ticket.user.application.dto;

import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.user.domain.User;
import com.sparta.ticket.user.domain.UserStatus;

public record SignupResult(Long userId, String email, String name, Role role, UserStatus status) {

    public static SignupResult from(User user) {
        return new SignupResult(user.getId(), user.getEmail(), user.getName(), user.getRole(), user.getStatus());
    }
}
