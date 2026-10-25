package com.sparta.ticket.fakepg.presentation.interceptor;

import com.sparta.ticket.fakepg.domain.exception.PgErrorCode;
import com.sparta.ticket.fakepg.domain.exception.PgException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 토스 API의 Basic 인증 흉내. 헤더 값은 {@code Basic base64("시크릿 키:")}다(시크릿 키 뒤에 콜론).
 * 실제 PG처럼 인증이 틀리면 401 UNAUTHORIZED_KEY다.
 */
@Component
public class TossAuthInterceptor implements HandlerInterceptor {

    private final String expected;

    public TossAuthInterceptor(@Value("${fake-pg.toss.secret-key}") String secretKey) {
        this.expected =
                "Basic " + Base64.getEncoder().encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!expected.equals(request.getHeader(HttpHeaders.AUTHORIZATION))) {
            throw new PgException(PgErrorCode.UNAUTHORIZED_KEY);
        }
        return true;
    }
}
