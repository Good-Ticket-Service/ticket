package com.sparta.ticket.payment.infrastructure.config;

import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

/**
 * Note: @EnableFeignClients를 메인 클래스가 아니라 별도 설정에 둔다. 메인 클래스에 있으면 @WebMvcTest 같은 슬라이스 테스트에도
 * FeignClient 등록이 따라 들어와, Feign 자동 설정이 없는 슬라이스 컨텍스트가 뜨지 못한다. 별도 @Configuration은 슬라이스에서 빠진다.
 */
@Configuration
@EnableFeignClients(basePackages = "com.sparta.ticket.payment")
public class FeignConfig {
}
