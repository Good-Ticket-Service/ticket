package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.SettlementSourceJdbc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/** 새로운 조회의 직접 SQL 사전 기준선. 실제 API·혼합 환불·동시 유입 시험과 구분한다. */
@EnabledIfSystemProperty(named = "projection.measure", matches = "true")
class SettlementProjectionPerformanceTest {
    @Test
    void directSourceBaseline() throws Exception {
        int rows = Integer.getInteger("projection.rows", 100000);
        Path output = Path.of(System.getProperty("projection.output", "build/projection-performance"));
        Files.createDirectories(output);
        var json = new ObjectMapper();
        try (var postgres = new PostgreSQLContainer<>("postgres:16")) {
            postgres.start();
            var dataSource =
                    new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            try (var connection = dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new FileSystemResource("../sql/payment-service/schema.sql"));
            }
            var jdbc = new JdbcTemplate(dataSource);
            var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            long seedStart = System.nanoTime();
            transaction.executeWithoutResult(status -> ReconcilePerformanceTest.seed(jdbc, rows));
            jdbc.execute("ANALYZE");
            var reader = new SettlementSourceJdbc(jdbc);
            var samples = new ArrayList<Map<String, Object>>();
            for (int iteration = 0; iteration < 4; iteration++) {
                var before = counters(jdbc);
                long[] nanos = new long[1000];
                for (int query = 0; query < nanos.length; query++) {
                    long seller = 1 + (query + iteration * 97) % 1000;
                    long started = System.nanoTime();
                    var totals = reader.totals(seller, null, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
                    nanos[query] = System.nanoTime() - started;
                    // Note: 기존 fixture는 g%1000+1 판매자, 일정 가격의 정상 SALE만 만든다.
                    long count = rows / 1000 + (seller >= 2 && seller <= rows % 1000 + 1 ? 1 : 0);
                    assertThat(totals.saleCount()).isEqualTo(count);
                    assertThat(totals.saleSellerAmount()).isEqualTo(count * 8680);
                    assertThat(totals.refundCount()).isZero();
                }
                Arrays.sort(nanos);
                var sample = new LinkedHashMap<String, Object>();
                sample.put("variant", "A0_SOURCE_DIAGNOSTIC");
                sample.put("iteration", iteration);
                sample.put("warmup", iteration == 0);
                sample.put("queries", nanos.length);
                sample.put("queryP50Ms", nanos[500] / 1e6);
                sample.put("queryP95Ms", nanos[950] / 1e6);
                sample.put("queryP99Ms", nanos[990] / 1e6);
                sample.put("databaseBefore", before);
                sample.put("databaseAfter", counters(jdbc));
                samples.add(sample);
                json.writerWithDefaultPrettyPrinter()
                        .writeValue(
                                output.resolve("A0-" + rows + "-samples.json").toFile(), samples);
            }
            String plan = jdbc.queryForObject(
                    "EXPLAIN(ANALYZE,BUFFERS,WAL,FORMAT JSON) "
                            + SettlementSourceJdbc.TOTALS.formatted(
                                    SettlementSourceJdbc.SOURCE
                                            + " AND t.seller_id=1 AND t.occurred_at>=timestamp '2026-10-01' AND t.occurred_at<timestamp '2026-11-01'"),
                    String.class);
            Files.writeString(output.resolve("A0-" + rows + "-plan.json"), plan);
            var environment = new LinkedHashMap<String, Object>();
            environment.put("sourceRows", rows);
            environment.put("sellerCount", 1000);
            environment.put("scheduleCount", 1);
            environment.put("refundCount", 0);
            environment.put(
                    "scope", "preliminary SQL-only warm-cache diagnostic; no HTTP, concurrent arrivals or refunds");
            environment.put("elapsedIncludingSeedAndQueriesMs", (System.nanoTime() - seedStart) / 1000000);
            environment.put("postgres", jdbc.queryForObject("SELECT version()", String.class));
            environment.put("java", System.getProperty("java.version"));
            environment.put(
                    "settings",
                    jdbc.queryForList(
                            "SELECT name,setting FROM pg_settings WHERE name IN ('shared_buffers','work_mem','max_parallel_workers_per_gather')"));
            environment.put(
                    "financial",
                    jdbc.queryForMap("SELECT sum(debit) AS debit,sum(credit) AS credit FROM p_ledger_entries"));
            environment.put(
                    "containerCpu",
                    postgres.execInContainer("cat", "/sys/fs/cgroup/cpu.stat").getStdout());
            environment.put(
                    "sourceSha256",
                    com.sparta.ticket.payment.domain.model.settlement.projection.SettlementFact.hash(
                            Files.readString(
                                    Path.of(
                                            "src/main/java/com/sparta/ticket/payment/infrastructure/persistence/settlement/projection/SettlementSourceJdbc.java"))));
            json.writerWithDefaultPrettyPrinter()
                    .writeValue(
                            output.resolve("A0-" + rows + "-environment.json").toFile(), environment);
        }
    }

    private static Map<String, Object> counters(JdbcTemplate jdbc) {
        return jdbc.queryForMap(
                "SELECT blks_read,blks_hit,temp_bytes,deadlocks,pg_current_wal_lsn()::text AS wal_lsn FROM pg_stat_database WHERE datname=current_database()");
    }
}
