package com.sparta.ticket.fakepg.presentation.exception;

import com.sparta.ticket.fakepg.domain.exception.PgErrorCode;
import com.sparta.ticket.fakepg.domain.exception.PgException;
import com.sparta.ticket.fakepg.presentation.dto.toss.TossErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 모든 오류를 토스 형식 {@code {"code", "message"}}으로 바꾼다. */
@Slf4j
@RestControllerAdvice
public class FakePgExceptionHandler {

    @ExceptionHandler(PgException.class)
    public ResponseEntity<TossErrorResponse> handle(PgException e) {
        return response(e.getErrorCode(), e.getMessage());
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
        IllegalArgumentException.class
    })
    public ResponseEntity<TossErrorResponse> handleInvalidRequest(Exception e) {
        return response(PgErrorCode.INVALID_REQUEST, PgErrorCode.INVALID_REQUEST.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<TossErrorResponse> handleUnexpected(Exception e) {
        log.error("fake PG 처리 중 예상하지 못한 오류", e);
        return response(
                PgErrorCode.FAILED_INTERNAL_SYSTEM_PROCESSING,
                PgErrorCode.FAILED_INTERNAL_SYSTEM_PROCESSING.getMessage());
    }

    private static ResponseEntity<TossErrorResponse> response(PgErrorCode code, String message) {
        return ResponseEntity.status(code.getStatus()).body(new TossErrorResponse(code.name(), message));
    }
}
