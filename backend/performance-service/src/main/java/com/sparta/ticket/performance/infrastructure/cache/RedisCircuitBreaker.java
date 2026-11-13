package com.sparta.ticket.performance.infrastructure.cache;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;

/**
 * Redis 명령이 연속으로 실패하면 일정 시간 Redis를 건너뛴다(이슈 #398).
 * 장애 중 요청마다 명령 제한 시간(spring.data.redis.timeout)을 기다리지 않고 바로 DB로 가게 하려는 것이다.
 * 건너뛰는 시간이 지나면 다시 명령을 보낸다. 그때 또 실패하면 바로 다시 건너뛰고, 성공하면 연속 실패 수를 0으로 되돌린다.
 * Note: 연결 장애·제한 시간 초과처럼 Redis 자체가 실패한 경우만 센다. 값 형식 오류(역직렬화 실패)는 세지 않는다.
 */
@Slf4j
final class RedisCircuitBreaker {

    private final int failureThreshold;
    private final long openNanos;
    private final LongSupplier nanoTime;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile boolean open;
    private volatile long openUntil;

    RedisCircuitBreaker(int failureThreshold, Duration openDuration, LongSupplier nanoTime) {
        this.failureThreshold = failureThreshold;
        this.openNanos = openDuration.toNanos();
        this.nanoTime = nanoTime;
    }

    /** 명령을 보내도 되면 true. 건너뛰는 중이면 false다. */
    boolean allowRequest() {
        return !open || nanoTime.getAsLong() - openUntil >= 0;
    }

    void recordSuccess() {
        consecutiveFailures.set(0);
        if (open) {
            open = false;
            log.info("Redis 명령이 다시 성공해 공연 조회 캐시를 다시 사용한다.");
        }
    }

    void recordFailure(RuntimeException e) {
        if (consecutiveFailures.incrementAndGet() < failureThreshold) {
            return;
        }
        openUntil = nanoTime.getAsLong() + openNanos;
        if (!open) {
            open = true;
            log.warn(
                    "Redis 명령이 {}번 연속 실패해 {}ms 동안 공연 조회 캐시를 건너뛰고 DB로 응답한다. cause={}",
                    failureThreshold,
                    Duration.ofNanos(openNanos).toMillis(),
                    e.toString());
        }
    }

    boolean isOpen() {
        return open;
    }
}
