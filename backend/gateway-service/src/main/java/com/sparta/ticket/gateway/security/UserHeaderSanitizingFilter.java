package com.sparta.ticket.gateway.security;

import com.sparta.ticket.common.auth.UserHeaders;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.gateway.support.ErrorResponseWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 인증 1~2단계. Spring Security 체인(order -100)보다 먼저, 라우트 매칭보다도 먼저 모든 요청에 실행된다.
 * Note: 서비스는 X-User-* 헤더를 검증 없이 믿는다. 화이트리스트 요청까지 포함해 가장 먼저 지우지 않으면 누구나
 * X-User-Role: MASTER로 관리자가 된다. /internal/** 은 라우트가 없어서 GlobalFilter로는 막을 수 없기 때문에 WebFilter에서 막는다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class UserHeaderSanitizingFilter implements WebFilter {

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private final ErrorResponseWriter errorWriter;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // 1. 클라이언트가 보낸 X-User-* 헤더 제거
        ServerHttpRequest stripped = exchange.getRequest()
                .mutate()
                .headers(headers -> UserHeaders.ALL.forEach(headers::remove))
                .build();
        ServerWebExchange sanitized = exchange.mutate().request(stripped).build();

        // 2. /internal/** 는 외부에서 호출할 수 없다 (404)
        String path = stripped.getPath().value();
        if (PATH_MATCHER.match("/internal/**", path) || PATH_MATCHER.match("/internal", path)) {
            return errorWriter.write(sanitized, CommonErrorCode.NOT_FOUND);
        }
        return chain.filter(sanitized);
    }
}
