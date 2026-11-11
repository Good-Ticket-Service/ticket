package com.sparta.ticket.payment.infrastructure.settlement.projection;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 전역 완료 이력을 매번 재스캔하지 않고 미발행·보류·오류 부분 인덱스를 관측한다. */
@Component
@ConditionalOnProperty(
        prefix = "payment.settlement.projection",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ProjectionTelemetry {
    public enum Phase {
        TICK,
        RELAY,
        BASELINE,
        SINK,
        METRICS
    }

    private final JdbcTemplate jdbc;
    private final MeterRegistry meters;
    private final Map<String, AtomicReference<Double>> gauges = new LinkedHashMap<>();
    private long nextRefresh;

    public ProjectionTelemetry(JdbcTemplate jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.meters = meters;
        for (Phase phase : Phase.values())
            meters.counter("payment.settlement.projection.failures", "phase", phase.name());
        for (String name : java.util.List.of(
                "unpublished",
                "oldest_unpublished_seconds",
                "errors",
                "pending",
                "active",
                "fanout_age_seconds",
                "sample_timestamp")) {
            var value = new AtomicReference<>(Double.NaN);
            gauges.put(name, value);
            meters.gauge("payment.settlement.projection." + name.replace('_', '.'), value, AtomicReference::get);
        }
    }

    public void failure(Phase phase) {
        meters.counter("payment.settlement.projection.failures", "phase", phase.name())
                .increment();
    }

    public void refresh() {
        long now = System.nanoTime();
        if (now < nextRefresh) return;
        nextRefresh = now + TimeUnit.SECONDS.toNanos(15);
        try {
            var row = jdbc.queryForMap("""
                    WITH running AS (SELECT id FROM p_settlement_projection_builds WHERE status IN ('BUILDING','VERIFYING','READY','ACTIVE'))
                    SELECT
                      (SELECT count(*) FROM p_settlement_projection_deliveries d JOIN running b ON b.id=d.build_id WHERE d.published_at IS NULL) AS unpublished,
                      (SELECT coalesce(extract(epoch FROM CURRENT_TIMESTAMP-min(f.created_at)),0) FROM p_settlement_projection_deliveries d
                        JOIN running b ON b.id=d.build_id JOIN p_settlement_projection_facts f ON f.fact_key=d.fact_key WHERE d.published_at IS NULL) AS oldest_unpublished_seconds,
                      (SELECT count(*) FROM p_settlement_projection_errors e JOIN running b ON b.id=e.build_id WHERE NOT e.resolved) AS errors,
                      (SELECT count(*) FROM p_settlement_projection_payment_states s JOIN running b ON b.id=s.build_id WHERE s.pending) AS pending,
                      (SELECT count(*) FROM p_settlement_projection_head h JOIN p_settlement_projection_builds b ON b.id=h.build_id WHERE b.status='ACTIVE') AS active,
                      (SELECT coalesce(extract(epoch FROM CURRENT_TIMESTAMP-b.last_fanout_at),0) FROM p_settlement_projection_head h LEFT JOIN p_settlement_projection_builds b ON b.id=h.build_id WHERE h.id=1) AS fanout_age_seconds,
                      extract(epoch FROM CURRENT_TIMESTAMP) AS sample_timestamp
                    """);
            row.forEach((key, value) -> gauges.get(key).set(((Number) value).doubleValue()));
        } catch (RuntimeException exception) {
            // Note: 관측 실패를 정상 0건으로 덮지 않고 마지막 성공 시각과 실패 횟수를 보존한다.
            failure(Phase.METRICS);
        }
    }
}
