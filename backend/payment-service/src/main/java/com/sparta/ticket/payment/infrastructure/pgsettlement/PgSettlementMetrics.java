package com.sparta.ticket.payment.infrastructure.pgsettlement;

import com.sparta.ticket.payment.application.service.pgsettlement.PgSettlementTransactions;
import com.sparta.ticket.payment.domain.model.pgsettlement.PgSettlement;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PgSettlementMetrics implements JobExecutionListener {
    private final MeterRegistry meters;
    private final PgSettlementTransactions transactions;
    private final com.sparta.ticket.payment.application.service.pgsettlement.PgSettlementProperties properties;
    private final Map<String, AtomicReference<Double>> values = new ConcurrentHashMap<>();

    public PgSettlementMetrics(
            MeterRegistry meters,
            PgSettlementTransactions transactions,
            com.sparta.ticket.payment.application.service.pgsettlement.PgSettlementProperties properties) {
        this.meters = meters;
        this.transactions = transactions;
        this.properties = properties;
        for (String provider : List.of("TOSS", "KAKAO"))
            for (var finding : PgSettlement.Finding.values()) {
                register("open", provider, finding.name());
                register("open.amount", provider, finding.name());
                register("oldest", provider, finding.name());
            }
        for (String account : List.of("PG_CLEARING", "PG_SUSPENSE", "PG_PAYABLE", "CASH"))
            register("last.job.balance", "ALL", account);
        for (String provider : List.of("TOSS", "KAKAO")) {
            register("open", provider, "SUSPENSE");
            register("open.amount", provider, "SUSPENSE");
            register("oldest", provider, "SUSPENSE");
        }
    }

    private void register(String metric, String provider, String kind) {
        var value = new AtomicReference<>(0.0);
        values.put(metric + ":" + provider + ":" + kind, value);
        meters.gauge(
                "payment.pg.settlement." + metric,
                io.micrometer.core.instrument.Tags.of("provider", provider, "kind", kind),
                value,
                AtomicReference::get);
    }

    @Scheduled(
            initialDelayString = "${payment.pg.metrics-delay-ms:60000}",
            fixedDelayString = "${payment.pg.metrics-delay-ms:60000}")
    public void scheduledRefresh() {
        if (properties.enabled()) refresh();
    }

    public void refresh() {
        // 집계가 실패하면 기존 값을 지우지 않고 실패를 별도 경보에 남긴다.
        try {
            var rows = transactions.metrics();
            values.forEach((key, value) -> {
                if (!key.startsWith("last.job.balance:")) value.set(0.0);
            });
            for (var row : rows) {
                String key = row.get("provider") + ":" + row.get("finding");
                values.get("open:" + key).set(((Number) row.get("count")).doubleValue());
                values.get("open.amount:" + key).set(((Number) row.get("amount")).doubleValue());
                values.get("oldest:" + key).set(((Number) row.get("oldest_seconds")).doubleValue());
            }
        } catch (RuntimeException e) {
            meters.counter("payment.pg.settlement.metrics.failures").increment();
        }
    }

    @Override
    public void afterJob(JobExecution execution) {
        String provider = execution.getJobParameters().getString("provider");
        meters.counter(
                        "payment.pg.settlement.jobs",
                        "provider",
                        provider,
                        "status",
                        execution.getStatus().name())
                .increment();
        if (execution.getStartTime() != null && execution.getEndTime() != null)
            meters.timer("payment.pg.settlement.duration", "provider", provider)
                    .record(Duration.between(execution.getStartTime(), execution.getEndTime()));
        if (execution.getStatus() == BatchStatus.COMPLETED) {
            refresh();
            try {
                var balances = transactions.balances();
                values.get("last.job.balance:ALL:PG_CLEARING").set((double) balances.pgClearing());
                values.get("last.job.balance:ALL:PG_SUSPENSE").set((double) balances.pgSuspense());
                values.get("last.job.balance:ALL:PG_PAYABLE").set((double) balances.pgPayable());
                values.get("last.job.balance:ALL:CASH").set((double) balances.bookCash());
            } catch (RuntimeException e) {
                meters.counter("payment.pg.settlement.metrics.failures").increment();
            }
        }
    }
}
