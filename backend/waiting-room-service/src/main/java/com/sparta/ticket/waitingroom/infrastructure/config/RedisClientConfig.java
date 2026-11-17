package com.sparta.ticket.waitingroom.infrastructure.config;

import io.lettuce.core.TimeoutOptions;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Lettuce 클라이언트 옵션. Spring Boot는 Redis 명령마다 타임아웃 타이머를 거는 Lettuce 기능(TimeoutOptions)을 켜는데,
 * 이 서비스는 Redis를 동기 방식(RedisTemplate)으로만 써서 응답 기다림의 시간 제한을 Spring Data Redis가 이미 건다
 * (spring.data.redis.timeout). 명령별 타이머는 같은 일을 한 번 더 하는 중복이라 끈다.
 *
 * <p>명령별 타이머는 명령을 보내기 전에 공유 타이머 큐에 들어간다. 순간 몰림 때 이 큐가 차서 덩어리를 새로 붙이는 동안 다른 처리 스레드는
 * 명령을 보내지 못하고 기다리고, 붙이던 스레드가 CPU를 뺏기면 그 대기가 수백 ms로 길어져 요청 처리가 멈춘다(#464).
 * 비동기·리액티브 Redis 호출을 새로 쓰면 그쪽에는 시간 제한이 없으니 이 설정을 다시 본다.
 */
@Configuration
public class RedisClientConfig {

    @Bean
    public LettuceClientOptionsBuilderCustomizer disableCommandTimeoutTimer() {
        // Boot가 TimeoutOptions.enabled()를 넣은 뒤에 이 커스터마이저가 돌아 덮어쓴다
        return builder -> builder.timeoutOptions(
                TimeoutOptions.builder().timeoutCommands(false).build());
    }
}
