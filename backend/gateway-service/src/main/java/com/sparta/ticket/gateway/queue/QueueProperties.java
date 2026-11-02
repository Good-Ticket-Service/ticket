package com.sparta.ticket.gateway.queue;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 대기열 입장 토큰 검사 설정.
 *
 * @param enabled 대기열을 쓰는지. 꺼져 있으면 좌석 API에서 입장 토큰을 검사하지 않는다. reservation-service의 reservation.queue.enabled와 같은 값(QUEUE_ENABLED)을 쓴다
 * @param tokenSecret 입장 토큰 HS256 서명 키. reservation-service가 발급에 쓰는 키(QUEUE_TOKEN_SECRET)와 같다
 */
@ConfigurationProperties(prefix = "queue")
public record QueueProperties(@DefaultValue("false") boolean enabled, String tokenSecret) {}
