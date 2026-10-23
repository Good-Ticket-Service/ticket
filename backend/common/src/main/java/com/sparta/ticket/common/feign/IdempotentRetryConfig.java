package com.sparta.ticket.common.feign;

import feign.Retryer;
import org.springframework.context.annotation.Bean;

/**
 * GET과 멱등 API만 최대 3회 재시도한다. 필요한 FeignClient에만 붙인다.
 * {@code @FeignClient(name = "event-service", configuration = IdempotentRetryConfig.class)}
 * Note: 일부러 @Configuration을 붙이지 않는다. 애플리케이션 컨텍스트에 빈으로 등록되면 모든 FeignClient에 적용되고,
 * 예약 생성 같은 멱등하지 않은 POST까지 타임아웃 때 다시 보내 중복 예약을 만들 수 있다.
 */
public class IdempotentRetryConfig {

    @Bean
    public Retryer retryer() {
        return new Retryer.Default(100, 1000, 3);
    }
}
