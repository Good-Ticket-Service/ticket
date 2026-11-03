package com.sparta.ticket.performance.application.dto;

import java.time.Duration;

/** 일반 재시도와 분리한 결과 불명 확인 횟수·간격·호출 임대. */
public record SeatSetupRecoveryPolicy(int maxAttempts, Duration firstDelay, Duration maxDelay, Duration lease) {
    public SeatSetupRecoveryPolicy {
        if (maxAttempts < 1
                || firstDelay.isNegative()
                || firstDelay.isZero()
                || maxDelay.compareTo(firstDelay) < 0
                || lease.isNegative()
                || lease.isZero()) {
            throw new IllegalArgumentException("복구 횟수·간격·임대 설정이 올바르지 않습니다.");
        }
    }

    public Duration delayAfter(int attempt) {
        Duration delay = firstDelay.multipliedBy(1L << Math.min(Math.max(attempt - 1, 0), 20));
        return delay.compareTo(maxDelay) > 0 ? maxDelay : delay;
    }
}
