package com.sparta.ticket.common.autoconfigure;

import com.sparta.ticket.common.config.CommonWebMvcConfigurer;
import com.sparta.ticket.common.exception.GlobalExceptionHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * MVC 서비스 공통: 공통 에러 응답 변환, {@code @CurrentUser}, 검색 Pageable 규칙, {@code @RequireRole}.
 * 서비스가 GlobalExceptionHandler를 상속한 빈을 직접 정의하면 공통 빈은 빠진다. 끄기: {@code common.web.enabled=false}
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "common.web", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CommonWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    @Bean
    public CommonWebMvcConfigurer commonWebMvcConfigurer() {
        return new CommonWebMvcConfigurer();
    }
}
