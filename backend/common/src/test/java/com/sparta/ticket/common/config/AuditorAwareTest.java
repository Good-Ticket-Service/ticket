package com.sparta.ticket.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.common.auth.UserHeaders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.AuditorAware;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class AuditorAwareTest {

    private final AuditorAware<String> auditorAware = new HeaderAuditorAware();

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("요청에 X-Username이 있으면 그 값을 기록한다")
    void usesUsernameHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(UserHeaders.USERNAME, "hong01");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertThat(auditorAware.getCurrentAuditor()).contains("hong01");
    }

    @Test
    @DisplayName("헤더가 없는 요청과 요청 밖의 작업은 SYSTEM으로 기록한다")
    void fallsBackToSystem() {
        assertThat(auditorAware.getCurrentAuditor()).contains("SYSTEM");

        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        assertThat(auditorAware.getCurrentAuditor()).contains("SYSTEM");
    }
}
