package com.sparta.ticket.common.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.common.feign.FeignErrorDecoder;
import com.sparta.ticket.common.feign.FeignHeaderInterceptor;
import feign.RequestInterceptor;
import feign.codec.ErrorDecoder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 모든 FeignClient에 적용되는 공통 처리: X-User-* 헤더 전달, 하위 서비스 에러 변환.
 * 재시도(IdempotentRetryConfig)는 멱등 API에만 붙이므로 여기 넣지 않는다.
 * 서비스가 ErrorDecoder를 직접 정의하면 공통 디코더는 빠진다. 끄기: {@code common.feign.enabled=false}
 */
@AutoConfiguration(after = JacksonAutoConfiguration.class)
@ConditionalOnClass({RequestInterceptor.class, ErrorDecoder.class})
@ConditionalOnProperty(prefix = "common.feign", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CommonFeignAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public FeignHeaderInterceptor feignHeaderInterceptor() {
        return new FeignHeaderInterceptor();
    }

    @Bean
    @ConditionalOnMissingBean(ErrorDecoder.class)
    public FeignErrorDecoder feignErrorDecoder(ObjectProvider<ObjectMapper> objectMapper) {
        return new FeignErrorDecoder(objectMapper.getIfAvailable(ObjectMapper::new));
    }
}
