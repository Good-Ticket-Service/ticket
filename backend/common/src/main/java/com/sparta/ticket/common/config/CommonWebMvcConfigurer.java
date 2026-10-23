package com.sparta.ticket.common.config;

import com.sparta.ticket.common.auth.CurrentUserArgumentResolver;
import com.sparta.ticket.common.auth.RequireRoleInterceptor;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * {@code @CurrentUser}, 검색 Pageable 규칙, {@code @RequireRole} 검사를 MVC에 등록한다. CommonWebAutoConfiguration이 빈으로 등록한다.
 *
 * Note: Spring Data도 WebMvcConfigurer로 Pageable 처리기를 등록한다. 처리기 목록은 등록 순서대로 먼저 맞는 것이 쓰이므로,
 * 이 설정을 가장 먼저 실행해 공통 규칙(SearchPageableArgumentResolver)이 Pageable을 맡게 한다.
 * 순서는 클래스의 @Order 대신 Ordered로 정한다. @Bean으로 등록해도 인스턴스의 순서가 확실히 반영된다.
 */
public class CommonWebMvcConfigurer implements WebMvcConfigurer, Ordered {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserArgumentResolver());
        resolvers.add(new SearchPageableArgumentResolver());
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RequireRoleInterceptor());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
