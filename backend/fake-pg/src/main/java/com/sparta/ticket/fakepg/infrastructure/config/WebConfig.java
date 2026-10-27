package com.sparta.ticket.fakepg.infrastructure.config;

import com.sparta.ticket.fakepg.presentation.interceptor.KakaoAuthInterceptor;
import com.sparta.ticket.fakepg.presentation.interceptor.TossAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final TossAuthInterceptor tossAuthInterceptor;
    private final KakaoAuthInterceptor kakaoAuthInterceptor;

    /** 토스·카카오 호환 API에만 인증을 건다. 결제 인증 시뮬레이션과 관리 API(/fake/**)는 로컬 테스트용이라 인증이 없다. */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tossAuthInterceptor).addPathPatterns("/v1/**");
        registry.addInterceptor(kakaoAuthInterceptor).addPathPatterns("/online/**");
    }
}
