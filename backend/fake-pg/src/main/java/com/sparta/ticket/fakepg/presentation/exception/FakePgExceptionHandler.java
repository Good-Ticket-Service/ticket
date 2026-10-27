package com.sparta.ticket.fakepg.presentation.exception;

import com.sparta.ticket.fakepg.domain.exception.PgErrorCode;
import com.sparta.ticket.fakepg.domain.exception.PgException;
import com.sparta.ticket.fakepg.presentation.dto.kakao.KakaoErrorResponse;
import com.sparta.ticket.fakepg.presentation.dto.toss.TossErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 모든 오류를 PG 형식으로 바꾼다. 카카오 경로는 {@code {"error_code", "error_message"}}, 그 밖은 토스 형식 {@code {"code", "message"}}이다. */
@Slf4j
@RestControllerAdvice
public class FakePgExceptionHandler {

    @ExceptionHandler(PgException.class)
    public ResponseEntity<Object> handle(PgException e, HttpServletRequest request) {
        return response(request, e.getErrorCode(), e.getMessage());
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
        IllegalArgumentException.class
    })
    public ResponseEntity<Object> handleInvalidRequest(Exception e, HttpServletRequest request) {
        return response(request, PgErrorCode.INVALID_REQUEST, PgErrorCode.INVALID_REQUEST.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("fake PG 처리 중 예상하지 못한 오류", e);
        return response(
                request,
                PgErrorCode.FAILED_INTERNAL_SYSTEM_PROCESSING,
                PgErrorCode.FAILED_INTERNAL_SYSTEM_PROCESSING.getMessage());
    }

    private static ResponseEntity<Object> response(HttpServletRequest request, PgErrorCode code, String message) {
        if (isKakao(request)) {
            return KakaoErrorResponse.of(code, message);
        }
        return ResponseEntity.status(code.getStatus()).body(new TossErrorResponse(code.name(), message));
    }

    private static boolean isKakao(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.startsWith("/online/") || uri.startsWith("/fake/kakao/");
    }
}
