package com.sparta.ticket.gateway.security;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param whitelist         인증 없이 통과시키는 경로
 * @param userCacheTtl      권한 재확인 결과 캐시 시간(60초)
 * @param userLookupTimeout 권한 재확인 한 번에 기다리는 최대 시간. 넘으면 503
 */
@ConfigurationProperties(prefix = "auth")
public record AuthProperties(
        List<String> whitelist,
        @DefaultValue("60s") Duration userCacheTtl,
        @DefaultValue("3s") Duration userLookupTimeout) {}
