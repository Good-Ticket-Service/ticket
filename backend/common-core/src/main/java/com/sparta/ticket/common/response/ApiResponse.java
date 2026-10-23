package com.sparta.ticket.common.response;

import org.springframework.http.HttpStatus;

/**
 * 성공 응답 공통 형식. {@code status}, {@code message}, {@code data}.
 */
public record ApiResponse<T>(int status, String message, T data) {

    private static final String SUCCESS = "SUCCESS";

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(HttpStatus.OK.value(), SUCCESS, data);
    }

    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(HttpStatus.OK.value(), message, data);
    }

    public static <T> ApiResponse<T> created(String message, T data) {
        return new ApiResponse<>(HttpStatus.CREATED.value(), message, data);
    }
}
