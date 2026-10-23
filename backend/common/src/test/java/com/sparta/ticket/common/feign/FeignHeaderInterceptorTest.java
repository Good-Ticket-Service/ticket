package com.sparta.ticket.common.feign;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.common.auth.UserHeaders;
import feign.RequestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class FeignHeaderInterceptorTest {

    private final FeignHeaderInterceptor interceptor = new FeignHeaderInterceptor();

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("현재 요청의 X-User-* 헤더를 Feign 요청에 그대로 싣는다")
    void propagatesUserHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(UserHeaders.USER_ID, "3fa85f64-5717-4562-b3fc-2c963f66afa6");
        request.addHeader(UserHeaders.ROLE, "MASTER");
        request.addHeader("Authorization", "Bearer token");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        assertThat(template.headers())
                .containsKeys(UserHeaders.USER_ID, UserHeaders.ROLE)
                .doesNotContainKey("Authorization");
    }

    @Test
    @DisplayName("요청 밖에서 호출하면 아무 헤더도 넣지 않는다")
    void skipsOutsideRequest() {
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        assertThat(template.headers()).isEmpty();
    }
}
