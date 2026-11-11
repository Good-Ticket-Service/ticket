package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.jdbc.core.JdbcTemplate;

/** 측정 전용. 전체 이력 대신 고정 활성 집계 키를 읽는 관측 비용도 pg_stat_statements에 포함한다. */
final class ProjectionProgressSampler implements AutoCloseable {
    record Source(long facts, long grossContributionAmount, long netContributionAmount) {}

    private final java.util.concurrent.ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final BufferedWriter writer;

    ProjectionProgressSampler(JdbcTemplate jdbc, String mode, AtomicReference<Source> source, Path output)
            throws Exception {
        writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8);
        var json = new ObjectMapper();
        executor.scheduleWithFixedDelay(
                () -> {
                    var sample = new LinkedHashMap<String, Object>();
                    sample.put("epochMs", System.currentTimeMillis());
                    sample.put("mode", mode);
                    sample.put(
                            "scope",
                            "acknowledged source counter versus committed totals; gross is SALE plus REFUND contribution amounts, not unpaid cash");
                    try {
                        Source before = source.get();
                        sample.put("sourceBefore", before);
                        if (!"A0".equals(mode)) {
                            var row = jdbc.queryForMap("""
                            SELECT coalesce(sum(t.sale_count+t.refund_count),0)::bigint AS facts,
                              coalesce(sum(t.sale_amount+t.refund_amount),0)::bigint AS gross,
                              coalesce(sum(t.sale_amount-t.refund_amount),0)::bigint AS net
                            FROM p_settlement_projection_totals t
                            JOIN p_settlement_projection_head h ON h.build_id=t.build_id
                            """);
                            Source after = source.get();
                            sample.put("sourceAfter", after);
                            sample.put("projected", row);
                            long projectedFacts = ((Number) row.get("facts")).longValue();
                            long projectedGross = ((Number) row.get("gross")).longValue();
                            boolean stable = before == after
                                    && projectedFacts <= after.facts()
                                    && projectedGross <= after.grossContributionAmount();
                            sample.put("stableAcknowledgementSnapshot", stable);
                            if (stable) {
                                sample.put("unappliedFactsEstimate", after.facts() - projectedFacts);
                                sample.put(
                                        "unappliedGrossContributionAmountEstimate",
                                        after.grossContributionAmount() - projectedGross);
                                sample.put(
                                        "unappliedNetContributionAmountEstimate",
                                        after.netContributionAmount() - ((Number) row.get("net")).longValue());
                            }
                        }
                        sample.put("samplingMs", (System.currentTimeMillis() - (long) sample.get("epochMs")));
                    } catch (Exception exception) {
                        sample.put("error", exception.getClass().getSimpleName());
                    }
                    try {
                        writer.write(json.writeValueAsString(sample));
                        writer.newLine();
                        writer.flush();
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
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) throw new IllegalStateException("집계 관측 스레드 종료 실패");
        }
        writer.close();
    }
}
