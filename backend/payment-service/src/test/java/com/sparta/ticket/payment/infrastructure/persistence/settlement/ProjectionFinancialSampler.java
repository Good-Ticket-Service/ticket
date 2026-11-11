package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;

/** 금융 부하의 워커 적체·최장 나이·집계 미반영을 같은 SQL 비용으로 관측한다. */
final class ProjectionFinancialSampler implements AutoCloseable {
    private final java.util.concurrent.ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    ProjectionFinancialSampler(JdbcTemplate jdbc, Path output) {
        var json = new ObjectMapper();
        executor.scheduleWithFixedDelay(
                () -> {
                    var row = new LinkedHashMap<String, Object>();
                    row.put("epochMs", System.currentTimeMillis());
                    try {
                        row.put("worker", jdbc.queryForMap("""
                        SELECT count(*) FILTER(WHERE status='PENDING') AS pending,
                          count(*) FILTER(WHERE status='BLOCKED') AS blocked,
                          count(*) FILTER(WHERE status='DONE') AS completed,
                          coalesce(max(extract(epoch FROM ((clock_timestamp() AT TIME ZONE 'Asia/Seoul')-created_at)))
                            FILTER(WHERE status<>'DONE'),0) AS oldest_unfinished_seconds
                        FROM p_settlement_jobs
                        """));
                        row.put("projection", jdbc.queryForMap("""
                        SELECT count(*) AS unapplied,
                          coalesce(max(extract(epoch FROM (clock_timestamp()-f.created_at))),0) AS oldest_unapplied_seconds,
                          coalesce(sum(f.amount),0) AS unapplied_gross_contribution_amount
                        FROM p_settlement_projection_facts f LEFT JOIN p_settlement_projection_applied a
                          ON a.fact_key=f.fact_key AND a.build_id=(SELECT build_id FROM p_settlement_projection_head WHERE id=1)
                        WHERE a.fact_key IS NULL
                        """));
                        row.put("samplingMs", System.currentTimeMillis() - (long) row.get("epochMs"));
                    } catch (Exception exception) {
                        row.put("error", exception.getClass().getSimpleName());
                    }
                    try {
                        Files.writeString(
                                output,
                                json.writeValueAsString(row) + "\n",
                                StandardOpenOption.CREATE,
                                StandardOpenOption.APPEND);
                    } catch (java.io.IOException exception) {
                        throw new java.io.UncheckedIOException(exception);
                    }
                },
                0,
                1,
                TimeUnit.SECONDS);
    }

    @Override
    public void close() throws Exception {
        executor.shutdown();
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
            executor.shutdownNow();
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) throw new IllegalStateException("금융 관측 스레드 종료 실패");
        }
    }
}
