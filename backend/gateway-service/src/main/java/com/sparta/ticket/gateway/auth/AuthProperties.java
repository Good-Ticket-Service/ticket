package com.sparta.ticket.gateway.auth;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param whitelist         인증 없이 통과시키는 경로
 * @param userCacheTtl      권한 재확인 결과 캐시 시간(60초)
 * @param userLookupTimeout 권한 재확인 한 번에 기다리는 최대 시간. 넘으면 503
 * @param userCacheMaxSize  권한 재확인 결과를 담아 둘 최대 회원 수. 동시에 요청하는 회원이 이보다 많으면 캐시가 밀려
 *                          user-service 조회가 늘어난다(대기열 폴링처럼 같은 회원이 몇 초마다 오는 경우)
 */
@ConfigurationProperties(prefix = "auth")
public record AuthProperties(
        List<String> whitelist,
        @DefaultValue("60s") Duration userCacheTtl,
        @DefaultValue("3s") Duration userLookupTimeout,
        @DefaultValue("10000") long userCacheMaxSize) {}
