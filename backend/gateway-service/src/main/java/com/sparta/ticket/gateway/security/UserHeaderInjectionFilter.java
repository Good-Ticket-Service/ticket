package com.sparta.ticket.gateway.security;

import com.sparta.ticket.common.auth.UserHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 인증 6단계. 인증이 끝난 요청에 검증된 사용자 값으로 X-User-* 헤더를 넣는다. Security 체인 안, 인가 단계 뒤에 등록한다.
 * 인증 정보는 ReactiveSecurityContextHolder(리액티브 체인의 Context)에서 꺼낸다. 서블릿의 ThreadLocal과 다르다.
 */
public class UserHeaderInjectionFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .map(Authentication::getPrincipal)
                .filter(UserSnapshot.class::isInstance)
                .cast(UserSnapshot.class)
                .map(user -> withUserHeaders(exchange, user))
                .defaultIfEmpty(exchange)
                .flatMap(chain::filter);
    }

    private ServerWebExchange withUserHeaders(ServerWebExchange exchange, UserSnapshot user) {
        ServerHttpRequest request = exchange.getRequest()
                .mutate()
                .header(UserHeaders.USER_ID, user.userId().toString())
                .header(UserHeaders.USERNAME, user.username())
                .header(UserHeaders.ROLE, user.role())
                .build();
        return exchange.mutate().request(request).build();
    }
}
