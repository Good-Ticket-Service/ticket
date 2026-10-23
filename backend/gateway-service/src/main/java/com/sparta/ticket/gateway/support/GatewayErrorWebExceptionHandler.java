package com.sparta.ticket.gateway.support;

import com.sparta.ticket.common.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 인증 밖에서 Gateway가 내는 오류도 공통 에러 형식으로 통일한다.
 * 라우트 없음 404, 대상 인스턴스 없음(Eureka 목록 비어 있음)·연결 실패 503, 라우트 응답 타임아웃 504.
 * Note: 공통 규칙은 하위 서비스 실패와 타임아웃을 모두 503 SERVICE_UNAVAILABLE로 정했다. Gateway 기본값인 504를 503으로 바꾼다.
 * Boot 기본 오류 처리기(order -1)보다 먼저 실행되도록 -2로 둔다.
 */
@Slf4j
@Component
@Order(-2)
@RequiredArgsConstructor
public class GatewayErrorWebExceptionHandler implements ErrorWebExceptionHandler {

    private final ErrorResponseWriter errorWriter;

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }
        CommonErrorCode errorCode = toErrorCode(ex);
        if (errorCode == CommonErrorCode.SERVICE_UNAVAILABLE) {
            log.warn(
                    "Gateway 라우팅 실패: {} {} cause={}",
                    exchange.getRequest().getMethod(),
                    exchange.getRequest().getPath(),
                    ex.toString());
        }
        return errorWriter.write(exchange, errorCode);
    }

    static CommonErrorCode toErrorCode(Throwable ex) {
        if (!(ex instanceof ErrorResponse response)) {
            return CommonErrorCode.SERVICE_UNAVAILABLE;
        }
        HttpStatusCode status = response.getStatusCode();
        return switch (status.value()) {
            case 400 -> CommonErrorCode.INVALID_REQUEST;
            case 401 -> CommonErrorCode.UNAUTHORIZED;
            case 403 -> CommonErrorCode.FORBIDDEN;
            case 404 -> CommonErrorCode.NOT_FOUND;
            case 405 -> CommonErrorCode.METHOD_NOT_ALLOWED;
            default ->
                status.is4xxClientError() ? CommonErrorCode.INVALID_REQUEST : CommonErrorCode.SERVICE_UNAVAILABLE;
        };
    }
}
