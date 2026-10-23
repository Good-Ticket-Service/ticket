package com.sparta.ticket.gateway.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.common.exception.CommonErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class GatewayErrorWebExceptionHandlerTest {

    @Test
    @DisplayName("라우트 응답 타임아웃(504)과 알 수 없는 예외는 SA대로 503 SERVICE_UNAVAILABLE")
    void timeoutBecomesServiceUnavailable() {
        assertThat(GatewayErrorWebExceptionHandler.toErrorCode(new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT)))
                .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE);
        assertThat(GatewayErrorWebExceptionHandler.toErrorCode(new IllegalStateException("connection refused")))
                .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("클라이언트 오류는 상태에 맞는 공통 코드로 바꾼다")
    void clientErrors() {
        assertThat(GatewayErrorWebExceptionHandler.toErrorCode(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .isEqualTo(CommonErrorCode.NOT_FOUND);
        assertThat(GatewayErrorWebExceptionHandler.toErrorCode(
                        new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED)))
                .isEqualTo(CommonErrorCode.METHOD_NOT_ALLOWED);
        assertThat(GatewayErrorWebExceptionHandler.toErrorCode(
                        new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE)))
                .isEqualTo(CommonErrorCode.INVALID_REQUEST);
    }
}
