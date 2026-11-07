package com.sparta.ticket.payment.infrastructure.pg;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** PG별 서킷 브레이커. 꺼져 있으면 {@link #get}이 null을 돌려준다. */
@Component
public class PgCircuitBreakers {

    private final Map<String, CircuitBreaker> breakers;

    @Autowired
    public PgCircuitBreakers(PgCircuitBreakerProperties properties, ObjectProvider<MeterRegistry> meters) {
        this(properties, meters.getIfAvailable());
    }

    PgCircuitBreakers(PgCircuitBreakerProperties properties, MeterRegistry meters) {
        if (!properties.enabled()) {
            this.breakers = Map.of();
            return;
        }
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(properties.windowSize())
                .minimumNumberOfCalls(properties.minimumCalls())
                .failureRateThreshold(properties.failureRatePercent())
                .slowCallDurationThreshold(properties.slowCallDuration())
                .slowCallRateThreshold(properties.slowCallRatePercent())
                .waitDurationInOpenState(properties.openDuration())
                .permittedNumberOfCallsInHalfOpenState(properties.halfOpenCalls())
                .maxWaitDurationInHalfOpenState(properties.halfOpenMaxWait())
                // Note: 열림 시간이 지나면 다음 호출을 기다리지 않고 시험 상태로 넘어간다. 호출이 뜸한 PG도 회복을 확인한다.
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
        if (meters != null) {
            TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meters);
        }
        this.breakers = Map.of(
                "TOSS", registry.circuitBreaker("pg-TOSS"),
                "KAKAO", registry.circuitBreaker("pg-KAKAO"));
    }

    public CircuitBreaker get(String provider) {
        return breakers.get(provider);
    }
}
