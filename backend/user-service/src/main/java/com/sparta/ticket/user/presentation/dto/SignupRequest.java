package com.sparta.ticket.user.presentation.dto;

import com.sparta.ticket.user.application.dto.SignupCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SignupRequest(
        @NotBlank @Pattern(regexp = "^[a-z0-9]{4,10}$", message = "4~10자의 소문자와 숫자만 사용할 수 있습니다.")
        String username,

        @NotBlank
        @Pattern(
                regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{8,15}$",
                message = "8~15자, 대문자·소문자·숫자·특수문자를 각각 1자 이상 포함해야 합니다.")
        String password,

        @NotBlank @Size(max = 50) String name) {

    public SignupCommand toCommand() {
        return new SignupCommand(username, password, name);
    }
}
