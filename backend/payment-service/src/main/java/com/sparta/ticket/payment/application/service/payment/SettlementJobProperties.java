package com.sparta.ticket.payment.application.service.payment;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 정산 반영 워커 설정(#304). 켜고 끄는 것은 payment.settlement.worker.enabled(스케줄러 조건)로 한다.
 *
 * @param batchSize 한 번 실행에서 처리할 최대 작업 수
 * @param maxRunSeconds 새 작업을 시작하는 시간 예산. 이미 시작한 반영은 끝까지 한다
 * @param maxAttempts 이만큼 실패하면 격리(BLOCKED)한다
 * @param retryDelay 첫 실패 뒤 재시도 간격. 실패할 때마다 두 배로 늘린다
 * @param maxRetryDelay 재시도 간격 상한
 */
@ConfigurationProperties("payment.settlement.worker")
public record SettlementJobProperties(
        @DefaultValue("100") int batchSize,
        @DefaultValue("15") long maxRunSeconds,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("30s") Duration retryDelay,
        @DefaultValue("10m") Duration maxRetryDelay) {

    public SettlementJobProperties {
        if (batchSize < 1 || maxRunSeconds < 1 || maxAttempts < 1)
            throw new IllegalArgumentException("정산 워커 batch-size·max-run-seconds·max-attempts는 1 이상이다");
        if (retryDelay == null
                || maxRetryDelay == null
                || retryDelay.isNegative()
                || retryDelay.isZero()
                || maxRetryDelay.compareTo(retryDelay) < 0)
            throw new IllegalArgumentException("정산 워커 retry-delay는 0보다 크고 max-retry-delay 이하다");
    }

    /** n번째 실패 뒤 간격: retryDelay × 2^(n-1), 상한 maxRetryDelay. */
    public Duration delayAfter(int failures) {
        Duration delay = retryDelay;
        for (int i = 1; i < failures && delay.compareTo(maxRetryDelay) < 0; i++) delay = delay.multipliedBy(2);
        return delay.compareTo(maxRetryDelay) > 0 ? maxRetryDelay : delay;
    }
}
