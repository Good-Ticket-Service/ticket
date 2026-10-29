package com.sparta.ticket.payment.infrastructure.observation;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class PaymentObservation {
    private final PaymentObservationTransactions transactions;
    private final MeterRegistry meters;
    private final Clock clock;
    private final Map<Key, Boolean> registered = new ConcurrentHashMap<>();
    private final AtomicReference<Map<Key, Double>> snapshot = new AtomicReference<>(Map.of());
    private volatile long lastSuccess;
    private volatile boolean successful;

    public PaymentObservation(
            PaymentObservationTransactions transactions, MeterRegistry meters, Clock clock, Environment environment) {
        this.transactions = transactions;
        this.meters = meters;
        this.clock = clock;
        Gauge.builder("payment.observation.last.success.timestamp.seconds", this, o -> o.lastSuccess)
                .register(meters);
        Gauge.builder("payment.observation.success", this, o -> o.successful ? 1 : 0)
                .register(meters);
        for (String job : List.of("recovery", "reconcile", "refund")) {
            String property = job.equals("refund") ? "payment.recovery.refund.enabled" : "payment." + job + ".enabled";
            double enabled = environment.getProperty(property, Boolean.class, true) ? 1 : 0;
            Gauge.builder("payment.job.enabled", () -> enabled).tag("task", job).register(meters);
        }
        // Note: 빈 DB도 0건과 수집 전 상태를 구분하고 모든 인스턴스가 같은 유한한 label 집합을 제공한다.
        for (String job : List.of("recovery", "reconcile", "refund", "ready_expiry")) {
            register(new Key("payment.db.jobs.due", List.of("task", job)));
            register(new Key("payment.db.jobs.oldest.due.age.seconds", List.of("task", job)));
        }
        register(new Key("payment.db.reconcile.unknown", List.of()));
        register(new Key("payment.db.reconcile.oldest.unknown.age.seconds", List.of()));
        for (String provider : List.of("TOSS", "KAKAO")) {
            for (String cause : List.of("inconsistent", "verification", "declined", "other"))
                register(new Key("payment.db.refunds.restricted", List.of("provider", provider, "cause", cause)));
            for (String status : List.of("READY", "IN_PROGRESS", "DONE", "FAILED", "CANCELED")) {
                register(new Key("payment.db.payments", List.of("provider", provider, "status", status)));
            }
            for (String status : List.of("WAITING", "REQUESTED", "FAILED")) {
                for (String mode : List.of("ACTIVE", "SLOW", "VERIFY_ONLY", "PAUSED")) {
                    for (String reason : List.of("USER_CANCEL", "SCHEDULE_CANCELED", "CONFIRM_FAILED", "AUTO_VOID")) {
                        var tags = List.of("provider", provider, "status", status, "mode", mode, "reason", reason);
                        for (String name : List.of(
                                "pending",
                                "exposure.won",
                                "oldest.record.age.seconds",
                                "oldest.restriction.age.seconds",
                                "restriction.time.unknown")) {
                            register(new Key("payment.db.refunds." + name, tags));
                        }
                    }
                }
            }
        }
    }

    private record Key(String name, List<String> tags) {}

    private void register(Key key) {
        registered.computeIfAbsent(key, ignored -> {
            Gauge.builder(key.name(), snapshot, s -> s.get().getOrDefault(key, 0.0))
                    .tags(Tags.of(key.tags().toArray(String[]::new)))
                    .register(meters);
            return true;
        });
    }

    public void refresh() {
        long started = System.nanoTime();
        try {
            Map<Key, Double> next = new HashMap<>();
            for (var sample : transactions.read()) {
                var key = new Key(sample.name(), sample.tags());
                register(key);
                next.put(key, sample.value());
            }
            snapshot.set(Map.copyOf(next));
            lastSuccess = clock.instant().getEpochSecond();
            successful = true;
        } catch (RuntimeException e) {
            successful = false;
            try {
                meters.counter("payment.observation.errors").increment();
            } catch (RuntimeException ignored) {
                // Note: 계측 오류 뒤에도 다음 스냅샷 수집을 유지한다.
            }
        } finally {
            try {
                meters.timer("payment.observation.duration")
                        .record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
            } catch (RuntimeException ignored) {
                // Note: 스케줄 실행이 계측 예외로 영구 종료되지 않게 한다.
            }
        }
    }
}
