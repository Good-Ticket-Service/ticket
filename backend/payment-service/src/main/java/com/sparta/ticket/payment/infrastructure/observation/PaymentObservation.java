package com.sparta.ticket.payment.infrastructure.observation;

import com.sparta.ticket.payment.application.service.settlement.SettlementReconciliationJob;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.SettlementReconcileJdbcAdapter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Clock;
import java.util.ArrayList;
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
        registerSettlement(environment);
    }

    /** 정산 지표(Task 20-11). 결제 지표와 같이 빈 DB에서도 모든 라벨 조합을 0으로 내, 0건과 수집 전을 구분한다. */
    private void registerSettlement(Environment environment) {
        Map<String, String> switches = Map.of(
                "statement", "payment.settlement.statement.enabled",
                "payout", "payment.settlement.payout.enabled",
                "payout_recovery", "payment.settlement.payout.recovery.enabled",
                "reconcile", "payment.settlement.reconcile.enabled");
        switches.forEach((task, property) -> {
            double enabled = environment.getProperty(property, Boolean.class, false) ? 1 : 0;
            Gauge.builder("settlement.job.enabled", () -> enabled)
                    .tag("task", task)
                    .register(meters);
        });
        for (String attention : PaymentObservationTransactions.PAYOUT_ATTENTIONS) {
            for (String name : List.of(
                    "settlement.db.payouts",
                    "settlement.db.payouts.amount.won",
                    "settlement.db.payouts.oldest.age.seconds"))
                register(new Key(name, List.of("attention", attention)));
        }
        for (String code : PaymentObservationTransactions.PAYOUT_FAILURES)
            register(new Key("settlement.db.payouts.failed", List.of("failure_code", code)));
        for (String task : PaymentObservationTransactions.SETTLEMENT_TASKS) {
            register(new Key("settlement.db.jobs.due", List.of("task", task)));
            register(new Key("settlement.db.jobs.oldest.due.age.seconds", List.of("task", task)));
        }
        for (String reason : PaymentObservationTransactions.HOLD_REASONS) {
            register(new Key("settlement.db.sales.held", List.of("reason", reason)));
            register(new Key("settlement.db.sales.held.oldest.age.seconds", List.of("reason", reason)));
        }
        for (String name : List.of(
                "settlement.db.sales.unposted",
                "settlement.db.deductions.open",
                "settlement.db.deductions.open.amount.won",
                "settlement.db.deductions.oldest.age.seconds",
                "settlement.reconcile.last.success.timestamp.seconds",
                "settlement.reconcile.provider.unreachable",
                "settlement.reconcile.last.failed")) register(new Key(name, List.of()));
        List<String> checks = new ArrayList<>(SettlementReconcileJdbcAdapter.checkNames());
        checks.add(SettlementReconciliationJob.PROVIDER_CHECK);
        for (String check : checks) register(new Key("settlement.reconcile.violations", List.of("check", check)));
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
