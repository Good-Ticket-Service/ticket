package com.sparta.ticket.payment.infrastructure.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Kafka를 비활성화해도 미해결 incident는 계속 관측한다. 최근 counter 증가만으로 해제하지 않는다. */
@Component
@Slf4j
@ConditionalOnProperty(
        name = "payment.settlement.consumer-recovery.observation-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class FinancialConsumerTelemetry {
    private final JdbcTemplate jdbc;
    private final MeterRegistry meters;
    private final Map<String, AtomicReference<Double>> values = new LinkedHashMap<>();

    public FinancialConsumerTelemetry(JdbcTemplate jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.meters = meters;
        for (String stage : java.util.List.of("POSTING", "PROJECTION")) {
            var value = new AtomicReference<>(Double.NaN);
            values.put(stage, value);
            io.micrometer.core.instrument.Gauge.builder(
                            "settlement.consumer.incidents.unresolved", value, AtomicReference::get)
                    .tag("stage", stage)
                    .register(meters);
        }
        for (String name : java.util.List.of(
                "settlement.posting.financial.pending",
                "settlement.posting.oldest.pending.seconds",
                "settlement.posting.unpublished",
                "settlement.consumer.replay.pending",
                "settlement.consumer.observation.timestamp")) {
            var value = new AtomicReference<>(Double.NaN);
            values.put(name, value);
            meters.gauge(name, value, AtomicReference::get);
        }
    }

    @Scheduled(fixedDelayString = "${payment.settlement.consumer-recovery.observation-delay-ms:15000}")
    public void refresh() {
        try {
            for (String stage : java.util.List.of("POSTING", "PROJECTION"))
                values.get(stage)
                        .set(jdbc.queryForObject(
                                "SELECT count(*)::float8 FROM p_financial_consumer_incidents WHERE stage=? AND status<>'RESOLVED'",
                                Double.class,
                                stage));
            var row = jdbc.queryForMap("""
                    SELECT count(*)::float8 AS pending,
                      count(*) FILTER(WHERE published_at IS NULL)::float8 AS unpublished,
                      coalesce(greatest(extract(epoch FROM (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')-min(created_at)),0),0)::float8 AS oldest
                    FROM p_settlement_jobs WHERE transport='KAFKA' AND status='PENDING'
                    """);
            values.get("settlement.posting.financial.pending").set(((Number) row.get("pending")).doubleValue());
            values.get("settlement.posting.unpublished").set(((Number) row.get("unpublished")).doubleValue());
            values.get("settlement.posting.oldest.pending.seconds").set(((Number) row.get("oldest")).doubleValue());
            values.get("settlement.consumer.replay.pending")
                    .set(jdbc.queryForObject(
                            "SELECT count(*)::float8 FROM p_financial_replay_commands WHERE status IN ('REQUESTED','PUBLISHED')",
                            Double.class));
            values.get("settlement.consumer.observation.timestamp").set(System.currentTimeMillis() / 1000.0);
        } catch (RuntimeException exception) {
            meters.counter("settlement.consumer.observation.errors").increment();
            log.warn("금융 Consumer 운영 관측 실패: {}", exception.getClass().getSimpleName());
        }
    }
}
