package com.sparta.ticket.user.application.dto;

public record TokenResult(String accessToken, String tokenType, long expiresIn) {

    public static TokenResult bearer(String accessToken, long expiresIn) {
        return new TokenResult(accessToken, "Bearer", expiresIn);
    }
}
