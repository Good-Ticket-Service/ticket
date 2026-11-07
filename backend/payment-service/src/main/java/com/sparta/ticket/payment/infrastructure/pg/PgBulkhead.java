package com.sparta.ticket.payment.infrastructure.pg;

import com.sparta.ticket.payment.infrastructure.pg.PgHttp.PgCall;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * PG별 동시 호출 상한. 자리를 {@link PgBulkheadProperties#maxWait()} 안에 얻지 못하면 PG로 보내지 않고
 * {@link PgCall.NotSent}(결제 서비스 자체 한도)를 돌려준다. 어댑터의 기존 분류가 그대로 적용된다: 승인·취소는 PG_BUSY 거절,
 * 준비는 실패, 조회는 결과 모름(다음 복구 회차에 다시 조회).
 *
 * Note: 보낸 뒤의 결과는 바꾸지 않는다. 자리는 호출이 끝나면(예외 포함) 반드시 돌려준다.
 */
@Component
public class PgBulkhead {

    private final PgBulkheadProperties properties;
    private final Map<String, Semaphore> slots;
    private final MeterRegistry meters;

    @Autowired
    public PgBulkhead(PgBulkheadProperties properties, ObjectProvider<MeterRegistry> meters) {
        this(properties, meters.getIfAvailable());
    }

    PgBulkhead(PgBulkheadProperties properties, MeterRegistry meters) {
        this.properties = properties;
        this.meters = meters;
        this.slots = Map.of(
                "TOSS", new Semaphore(properties.tossMaxConcurrent()),
                "KAKAO", new Semaphore(properties.kakaoMaxConcurrent()));
        if (meters != null && properties.enabled()) {
            slots.forEach((provider, slot) -> {
                int limit = "TOSS".equals(provider) ? properties.tossMaxConcurrent() : properties.kakaoMaxConcurrent();
                Gauge.builder("payment.pg.bulkhead.in_use", slot, s -> limit - s.availablePermits())
                        .tag("provider", provider)
                        .register(meters);
                Gauge.builder("payment.pg.bulkhead.limit", () -> limit)
                        .tag("provider", provider)
                        .register(meters);
                Gauge.builder("payment.pg.bulkhead.waiting", slot, Semaphore::getQueueLength)
                        .tag("provider", provider)
                        .register(meters);
            });
        }
    }

    public PgCall call(String provider, String operation, Supplier<PgCall> call) {
        Semaphore slot = properties.enabled() ? slots.get(provider) : null;
        if (slot == null) {
            return call.get();
        }
        long started = System.nanoTime();
        boolean acquired;
        try {
            acquired = slot.tryAcquire(properties.maxWait().toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        observe(provider, operation, acquired, System.nanoTime() - started);
        if (!acquired) {
            return new PgCall.NotSent("결제 서비스 PG 동시 호출 한도 초과", true);
        }
        try {
            return call.get();
        } finally {
            slot.release();
        }
    }

    private void observe(String provider, String operation, boolean acquired, long waitedNanos) {
        if (meters == null) return;
        try {
            meters.timer(
                            "payment.pg.bulkhead.wait",
                            "provider",
                            provider,
                            "operation",
                            operation,
                            "outcome",
                            acquired ? "acquired" : "rejected")
                    .record(waitedNanos, TimeUnit.NANOSECONDS);
        } catch (RuntimeException ignored) {
            // Note: 관측 실패로 PG 호출 판단을 바꾸지 않는다.
        }
    }
}
