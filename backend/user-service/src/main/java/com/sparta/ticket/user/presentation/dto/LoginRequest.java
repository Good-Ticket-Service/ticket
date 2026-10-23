package com.sparta.ticket.user.presentation.dto;

import com.sparta.ticket.user.application.dto.LoginCommand;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(@NotBlank String username, @NotBlank String password) {

    public LoginCommand toCommand() {
        return new LoginCommand(username, password);
    }
}
