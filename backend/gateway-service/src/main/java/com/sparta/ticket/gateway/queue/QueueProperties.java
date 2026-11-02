package com.sparta.ticket.gateway.queue;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 대기열 입장 토큰 검사 설정.
 *
 * @param enabled 대기열을 쓰는지. 꺼져 있으면 좌석 API에서 입장 토큰을 검사하지 않는다. reservation-service의 reservation.queue.enabled와 같은 값(QUEUE_ENABLED)을 쓴다
 * @param tokenSecret 입장 토큰 HS256 서명 키. reservation-service가 발급에 쓰는 키(QUEUE_TOKEN_SECRET)와 같다
 * @param scheduleCacheTtl 회차 → 공연 조회 결과 캐시 시간. 회차의 공연은 바뀌지 않으므로 길게 둔다
 * @param scheduleLookupTimeout 회차 조회 한 번에 기다리는 최대 시간. 넘으면 통과시키지 않고 503
 */
@ConfigurationProperties(prefix = "queue")
public record QueueProperties(
        @DefaultValue("false") boolean enabled,
        String tokenSecret,
        @DefaultValue("1h") Duration scheduleCacheTtl,
        @DefaultValue("3s") Duration scheduleLookupTimeout) {}
