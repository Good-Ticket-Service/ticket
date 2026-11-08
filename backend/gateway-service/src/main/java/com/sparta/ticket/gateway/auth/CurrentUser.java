package com.sparta.ticket.gateway.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import reactor.core.publisher.Mono;

/**
 * 인증을 마친 요청의 로그인 회원(UserVerifyingJwtConverter가 principal로 넣은 UserSnapshot)을 꺼낸다.
 * 인증 정보는 ReactiveSecurityContextHolder(리액티브 체인의 Context)에 있다. 서블릿의 ThreadLocal과 다르다.
 */
public final class CurrentUser {

    private CurrentUser() {}

    /** 로그인 회원. 인증 전이거나 화이트리스트 요청이면 빈 값이다. */
    public static Mono<UserSnapshot> get() {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .map(Authentication::getPrincipal)
                .filter(UserSnapshot.class::isInstance)
                .cast(UserSnapshot.class);
    }
}
