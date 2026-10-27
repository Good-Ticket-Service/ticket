package com.sparta.ticket.user.presentation;

import com.sparta.ticket.common.response.ApiResponse;
import com.sparta.ticket.user.application.AuthService;
import com.sparta.ticket.user.application.dto.SignupResult;
import com.sparta.ticket.user.application.dto.TokenResult;
import com.sparta.ticket.user.presentation.dto.LoginRequest;
import com.sparta.ticket.user.presentation.dto.SignupRequest;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 회원가입·로그인은 Public API라 문서의 전역 Bearer 요구사항을 비운다. */
@SecurityRequirements
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SignupResult> signup(@Valid @RequestBody SignupRequest request) {
        return ApiResponse.created("회원가입이 완료되었습니다.", authService.signup(request.toCommand()));
    }

    @PostMapping("/seller/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SignupResult> signupSeller(@Valid @RequestBody SignupRequest request) {
        return ApiResponse.created("회원가입이 완료되었습니다.", authService.signupSeller(request.toCommand()));
    }

    @PostMapping("/login")
    public ApiResponse<TokenResult> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok("로그인에 성공했습니다.", authService.login(request.toCommand()));
    }
}
