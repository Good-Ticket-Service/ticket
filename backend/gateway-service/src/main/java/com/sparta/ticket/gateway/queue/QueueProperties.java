package com.sparta.ticket.gateway.queue;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 대기열 입장 토큰 검사 설정. 켜고 끄는 설정은 없다. 대기열이 보호하는 회차만 검사한다(ProtectedScopes).
 *
 * @param tokenSecret 입장 토큰 HS256 서명 키. reservation-service가 발급에 쓰는 키(QUEUE_TOKEN_SECRET)와 같다
 * @param protectedScopesInterval 대기열 보호 목록을 다시 받는 간격. room 생성 때 openAt을 지금 + 1분 이후로 강제하므로 그보다 짧아야 한다
 * @param protectedScopesTimeout 보호 목록 조회 한 번에 기다리는 최대 시간. 넘으면 실패로 보고 마지막 목록을 계속 쓴다
 */
@ConfigurationProperties(prefix = "queue")
public record QueueProperties(
        String tokenSecret,
        @DefaultValue("10s") Duration protectedScopesInterval,
        @DefaultValue("1s") Duration protectedScopesTimeout) {}
