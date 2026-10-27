package com.sparta.ticket.user.presentation.dto;

import com.sparta.ticket.user.application.dto.SignupCommand;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 회원가입·판매자 회원가입 요청. role은 받지 않고 서버가 진입점에 따라 USER·SELLER로 만든다. */
public record SignupRequest(
        @NotBlank @Email @Size(max = 100) String email,

        @NotBlank
        @Pattern(
                regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{8,15}$",
                message = "8~15자, 대문자·소문자·숫자·특수문자를 각각 1자 이상 포함해야 합니다.")
        String password,

        @NotBlank @Size(max = 50) String name,

        @NotBlank @Pattern(regexp = "^01[016789]-\\d{3,4}-\\d{4}$", message = "010-1234-5678 형식이어야 합니다.")
        String phone) {

    public SignupCommand toCommand() {
        return new SignupCommand(email, password, name, phone);
    }
}
