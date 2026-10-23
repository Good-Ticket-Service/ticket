package com.sparta.ticket.common.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 에러 응답 공통 형식. {@code errors}는 VALIDATION_ERROR일 때만 포함한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        int status,
        String code,
        String message,
        String path,
        LocalDateTime timestamp,
        List<FieldError> errors
) {

    public record FieldError(String field, String reason) {
    }

    public static ErrorResponse of(ErrorCode errorCode, String message, String path) {
        return of(errorCode, message, path, null);
    }

    public static ErrorResponse of(ErrorCode errorCode, String message, String path, List<FieldError> errors) {
        return new ErrorResponse(
                errorCode.status().value(),
                errorCode.code(),
                message,
                path,
                LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS),
                errors
        );
    }
}
