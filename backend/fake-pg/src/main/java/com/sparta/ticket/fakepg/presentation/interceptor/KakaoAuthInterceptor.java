package com.sparta.ticket.fakepg.presentation.interceptor;

import com.sparta.ticket.fakepg.domain.exception.PgErrorCode;
import com.sparta.ticket.fakepg.domain.exception.PgException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** 카카오페이 API의 인증 흉내. 헤더 값은 {@code SECRET_KEY {시크릿 키}}다. 틀리면 401이다. */
@Component
public class KakaoAuthInterceptor implements HandlerInterceptor {

    private final String expected;

    public KakaoAuthInterceptor(@Value("${fake-pg.kakao.secret-key}") String secretKey) {
        this.expected = "SECRET_KEY " + secretKey;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!expected.equals(request.getHeader(HttpHeaders.AUTHORIZATION))) {
            throw new PgException(PgErrorCode.UNAUTHORIZED_KEY);
        }
        return true;
    }
}
