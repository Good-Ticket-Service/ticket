package com.sparta.ticket.reservation.booking.infrastructure.payment;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.reservation.booking.application.port.PaymentCallGuard;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * resilience4j Bulkhead와 Circuit Breaker로 만든 {@link PaymentCallGuard}. 결제 준비와 승인이 같은 것을 쓴다. 둘 다 같은 payment를 부르기 때문이다.
 *
 * <p>Bulkhead는 느린 payment를, Circuit Breaker는 멈춘 payment를 맡는다. Circuit Breaker는 느린 호출을 세지 않는다.
 * 느린 호출로 열면 PG가 느리지만 동작할 때 시험 호출이 매번 느려 결제를 거의 모두 거절하게 된다. 느릴 때는 Bulkhead가 동시에 기다리는 수만 묶는다.
 *
 * <p>지표는 resilience4j_bulkhead_*, resilience4j_circuitbreaker_*(name=payment)로 내보낸다.
 */
@Slf4j
@Component
@EnableConfigurationProperties(PaymentGuardProperties.class)
public class Resilience4jPaymentCallGuard implements PaymentCallGuard {

    private static final String NAME = "payment";

    /** 꺼져 있을 때 주는 호출. 아무것도 기록하지 않는다. */
    private static final Call UNGUARDED = new Call() {
        @Override
        public void answered() {}

        @Override
        public void unanswered(Throwable cause) {}

        @Override
        public void skipped() {}
    };

    private final boolean enabled;
    private final Bulkhead bulkhead;
    private final CircuitBreaker circuitBreaker;

    public Resilience4jPaymentCallGuard(PaymentGuardProperties properties, MeterRegistry meterRegistry) {
        this.enabled = properties.enabled();
        if (!enabled) {
            log.warn("결제 호출 보호가 꺼져 있다. payment가 느리거나 응답하지 않아도 사용자 요청의 결제 호출을 막지 않는다");
        }
        BulkheadRegistry bulkheads = BulkheadRegistry.of(BulkheadConfig.custom()
                .maxConcurrentCalls(properties.maxConcurrentCalls())
                // 자리가 날 때까지 기다리면 그동안 요청 스레드를 잡는다. 막으려던 일이라 기다리지 않는다
                .maxWaitDuration(Duration.ZERO)
                .build());
        CircuitBreakerRegistry circuitBreakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(properties.windowSize())
                .minimumNumberOfCalls(properties.minimumCalls())
                .failureRateThreshold(properties.failureRatePercent())
                .waitDurationInOpenState(properties.openDuration())
                .permittedNumberOfCallsInHalfOpenState(properties.halfOpenCalls())
                .maxWaitDurationInHalfOpenState(properties.halfOpenMaxWait())
                .build());
        this.bulkhead = bulkheads.bulkhead(NAME);
        this.circuitBreaker = circuitBreakers.circuitBreaker(NAME);
        this.circuitBreaker
                .getEventPublisher()
                .onStateTransition(event -> log.warn("결제 호출 차단 상태가 바뀌었다. {}", event.getStateTransition()));
        TaggedBulkheadMetrics.ofBulkheadRegistry(bulkheads).bindTo(meterRegistry);
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(circuitBreakers).bindTo(meterRegistry);
    }

    @Override
    public Call start() {
        if (!enabled) {
            return UNGUARDED;
        }
        if (!circuitBreaker.tryAcquirePermission()) {
            throw unavailable("결제 서비스가 응답하지 않아 잠시 결제를 받지 않습니다. 잠시 뒤 다시 시도해 주세요.");
        }
        if (!bulkhead.tryAcquirePermission()) {
            // 부르지 않은 호출이라 회로 판단에 넣지 않는다
            circuitBreaker.releasePermission();
            throw unavailable("결제 요청이 몰려 있습니다. 잠시 뒤 다시 시도해 주세요.");
        }
        return new GuardedCall(circuitBreaker.getCurrentTimestamp());
    }

    /** 테스트가 테스트마다 처음 상태로 되돌린다. 운영 코드는 부르지 않는다. */
    public void reset() {
        circuitBreaker.reset();
    }

    private static BusinessException unavailable(String message) {
        return new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, message);
    }

    /**
     * 한 호출의 결과를 한 번만 기록하고 Bulkhead 자리를 돌려준다. 기록 없이 두면 Bulkhead 자리가 줄고,
     * 반쯤 열린 상태에서는 시험 호출 자리도 줄어든다.
     */
    private final class GuardedCall implements Call {

        private final long startedAt;
        private final AtomicBoolean finished = new AtomicBoolean();

        private GuardedCall(long startedAt) {
            this.startedAt = startedAt;
        }

        @Override
        public void answered() {
            if (finished.compareAndSet(false, true)) {
                bulkhead.onComplete();
                circuitBreaker.onSuccess(elapsed(), circuitBreaker.getTimestampUnit());
            }
        }

        @Override
        public void unanswered(Throwable cause) {
            if (finished.compareAndSet(false, true)) {
                bulkhead.onComplete();
                circuitBreaker.onError(elapsed(), circuitBreaker.getTimestampUnit(), cause);
            }
        }

        @Override
        public void skipped() {
            if (finished.compareAndSet(false, true)) {
                bulkhead.onComplete();
                circuitBreaker.releasePermission();
            }
        }

        private long elapsed() {
            return circuitBreaker.getCurrentTimestamp() - startedAt;
        }
    }
}
