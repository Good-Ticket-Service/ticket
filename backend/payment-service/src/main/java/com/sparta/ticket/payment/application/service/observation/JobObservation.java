package com.sparta.ticket.payment.application.service.observation;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** 0건 정상 실행과 미실행·실행 중·내부 오류를 구분한다. PG 결과 불명은 잡 자체 실패와 별개다. */
public final class JobObservation {
    private final MeterRegistry meters;
    private final String job;
    private final AtomicInteger running = new AtomicInteger();
    private final AtomicLong started = new AtomicLong();
    private final AtomicLong succeeded = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    public JobObservation(MeterRegistry meters, String job) {
        this.meters = meters;
        this.job = job;
        Gauge.builder("payment.job.running", running, AtomicInteger::get)
                .tag("task", job)
                .register(meters);
        Gauge.builder("payment.job.last.start.timestamp.seconds", started, AtomicLong::get)
                .tag("task", job)
                .register(meters);
        Gauge.builder("payment.job.last.success.timestamp.seconds", succeeded, AtomicLong::get)
                .tag("task", job)
                .register(meters);
    }

    public void run(Runnable work) {
        long before = failures.get();
        started.set(Instant.now().getEpochSecond());
        running.incrementAndGet();
        boolean returned = false;
        try {
            work.run();
            returned = true;
        } finally {
            running.decrementAndGet();
            boolean success = returned && failures.get() == before;
            if (success) succeeded.set(Instant.now().getEpochSecond());
            try {
                meters.counter("payment.job.runs", "task", job, "outcome", success ? "success" : "error")
                        .increment();
            } catch (RuntimeException ignored) {
                // Note: 계측 때문에 다음 금융 실행을 중단하지 않는다.
            }
        }
    }

    public void failed() {
        failures.incrementAndGet();
    }

    public void claimed() {
        try {
            meters.counter("payment.job.claimed", "task", job).increment();
        } catch (RuntimeException ignored) {
            // Note: 점유 후 관측 실패가 실제 실행을 막지 않는다.
        }
    }
}
