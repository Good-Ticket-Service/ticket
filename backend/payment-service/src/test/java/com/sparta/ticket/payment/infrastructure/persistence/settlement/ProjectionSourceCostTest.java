package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.common.auth.UserHeaders;
import com.sparta.ticket.payment.support.LedgerTestFixture;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/** 원장 기록 TX의 source 저장 추가 비용. 결제 승인·정산 워커 전체 처리량으로 부르지 않는다. */
@EnabledIfSystemProperty(named = "projection.source.cost", matches = "true")
class ProjectionSourceCostTest {
    @TempDir
    Path temporary;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @Test
    void compareRealLedgerTransactionWithSourceOffAndOn() throws Exception {
        Path output =
                Path.of(System.getProperty("projection.output")).resolve("source-cost-" + System.currentTimeMillis());
        Files.createDirectories(output);
        int repeats = Integer.getInteger("projection.repeats", 3);
        for (int repeat = 0; repeat < repeats; repeat++)
            for (String mode : repeat % 2 == 0 ? List.of("OFF", "ON") : List.of("ON", "OFF")) {
                Path trial = output.resolve("repeat-" + repeat + "-" + mode);
                Files.createDirectories(trial);
                Process app = null, monitor = null;
                var report = new LinkedHashMap<String, Object>();
                report.put("mode", mode);
                report.put("repeat", repeat);
                report.put(
                        "scope",
                        "real LedgerTransactions.post; per-item lock and commit; source only; no PG, source publisher or full worker latency");
                try (var postgres = new PostgreSQLContainer<>("postgres:16")
                        .withCommand(
                                "postgres",
                                "-c",
                                "shared_preload_libraries=pg_stat_statements",
                                "-c",
                                "track_io_timing=on")
                        .withCreateContainerCmdModifier(command -> command.getHostConfig()
                                .withNanoCPUs(2000000000L)
                                .withMemory(2147483648L)
                                .withMemorySwap(2147483648L))) {
                    postgres.start();
                    try (var ds = ProjectionProcessMain.dataSource(
                            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                        try (var connection = ds.getConnection()) {
                            ScriptUtils.executeSqlScript(
                                    connection, new FileSystemResource("../sql/payment-service/schema.sql"));
                        }
                        var jdbc = new JdbcTemplate(ds);
                        jdbc.execute("CREATE EXTENSION pg_stat_statements");
                        new TransactionTemplate(new DataSourceTransactionManager(ds))
                                .executeWithoutResult(status -> ReconcilePerformanceTest.seed(jdbc, 2000));
                        LedgerTestFixture.clear(jdbc);
                        jdbc.execute("ANALYZE");
                        int port;
                        try (var socket = new ServerSocket(0)) {
                            port = socket.getLocalPort();
                        }
                        app = ProjectionProcessLauncher.startMain(
                                temporary,
                                ProjectionBenchmarkMain.class.getName(),
                                List.of(
                                        postgres.getJdbcUrl(),
                                        postgres.getUsername(),
                                        postgres.getPassword(),
                                        Integer.toString(port),
                                        mode,
                                        trial.resolve("app-metrics.jsonl").toString()),
                                trial.resolve("app.log"));
                        awaitReady(app, port);
                        String monitorScript = System.getProperty("projection.monitor.script", "");
                        if (!monitorScript.isBlank()) {
                            monitor = new ProcessBuilder(
                                            "python",
                                            monitorScript,
                                            trial.resolve("process-memory.jsonl")
                                                    .toString(),
                                            "api=" + app.pid(),
                                            "generator="
                                                    + ProcessHandle.current().pid())
                                    .redirectErrorStream(true)
                                    .redirectOutput(trial.resolve("monitor.log").toFile())
                                    .start();
                            report.put("memorySampler", "Win32 working set and private commit; 1 second samples");
                        }
                        JsonNode warm = post(port, 1, 1000);
                        assertThat(warm.path("data").path("transactions").asInt())
                                .isEqualTo(1000);
                        report.put("warmupTransactions", 1000);
                        report.put("warmup", warm);
                        jdbc.execute("SELECT pg_stat_statements_reset()");
                        String before = jdbc.queryForObject("SELECT pg_current_wal_lsn()::text", String.class);
                        report.put("databaseBefore", database(jdbc));
                        report.put("containerBefore", container(postgres));
                        long phase = System.currentTimeMillis();
                        JsonNode measured = post(port, 1001, 1000);
                        long finished = System.currentTimeMillis();
                        report.put("measurementStartEpochMs", phase);
                        report.put("measurementEndEpochMs", finished);
                        report.put("measurement", measured);
                        String after = jdbc.queryForObject("SELECT pg_current_wal_lsn()::text", String.class);
                        report.put(
                                "walBytes",
                                jdbc.queryForObject(
                                        "SELECT pg_wal_lsn_diff(?::pg_lsn,?::pg_lsn)", Long.class, after, before));
                        report.put("containerAfter", container(postgres));
                        report.put("databaseAfter", database(jdbc));
                        report.put(
                                "statements",
                                jdbc.queryForList(
                                        "SELECT query,calls,rows,total_exec_time,shared_blks_hit,shared_blks_read,shared_blks_dirtied,shared_blks_written,temp_blks_read,temp_blks_written,wal_records,wal_fpi,wal_bytes FROM pg_stat_statements ORDER BY total_exec_time DESC"));
                        report.put(
                                "percentiles", percentiles(measured.path("data").path("transactionMs")));
                        assertThat(measured.path("data").path("transactions").asInt())
                                .isEqualTo(1000);
                        assertThat(measured.path("data").path("sourceFacts").asLong())
                                .isEqualTo("ON".equals(mode) ? 2000 : 0);
                        assertThat(jdbc.queryForObject("SELECT count(*) FROM p_ledger_transactions", Long.class))
                                .isEqualTo(2000);
                        var finance = jdbc.queryForMap(
                                "SELECT sum(debit) AS debit,sum(credit) AS credit FROM p_ledger_entries");
                        report.put("financial", finance);
                        assertThat(finance.get("debit")).isEqualTo(finance.get("credit"));
                        report.put("postgres", jdbc.queryForObject("SELECT version()", String.class));
                        report.put("passed", true);
                    } finally {
                        stop(app);
                        stop(monitor);
                    }
                } finally {
                    json.writerWithDefaultPrettyPrinter()
                            .writeValue(trial.resolve("report.json").toFile(), report);
                }
            }
    }

    private JsonNode post(int port, int from, int count) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + "/__measure/source-cost?from=" + from + "&count=" + count))
                .header(UserHeaders.USER_ID, "1")
                .header(UserHeaders.USERNAME, "measurement")
                .header(UserHeaders.ROLE, "MASTER")
                .timeout(Duration.ofMinutes(2))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private void awaitReady(Process app, int port) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        while (System.nanoTime() < deadline) {
            assertThat(app.isAlive()).as("source cost JVM 생존").isTrue();
            try {
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health"))
                        .timeout(Duration.ofSeconds(2))
                        .GET()
                        .build();
                if (http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) return;
            } catch (java.io.IOException ignored) {
                Thread.sleep(100);
            }
        }
        throw new AssertionError("source cost JVM 기동 제한 시간 초과");
    }

    private static Map<String, Object> database(JdbcTemplate jdbc) {
        return jdbc.queryForMap(
                "SELECT blks_read,blks_hit,temp_bytes,deadlocks,blk_read_time,blk_write_time,pg_current_wal_lsn()::text AS wal_lsn FROM pg_stat_database WHERE datname=current_database()");
    }

    private static Map<String, String> container(PostgreSQLContainer<?> postgres) throws Exception {
        return Map.of(
                "cpu",
                postgres.execInContainer("cat", "/sys/fs/cgroup/cpu.stat").getStdout(),
                "memoryCurrent",
                postgres.execInContainer("cat", "/sys/fs/cgroup/memory.current").getStdout(),
                "io",
                postgres.execInContainer("cat", "/sys/fs/cgroup/io.stat").getStdout());
    }

    private static Map<String, Object> percentiles(JsonNode input) {
        var values = new ArrayList<Double>();
        input.forEach(value -> values.add(value.asDouble()));
        values.sort(Double::compareTo);
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        return Map.of(
                "method",
                "nearest-rank ceil(p*n)-1",
                "count",
                values.size(),
                "p50Ms",
                values.get((int) Math.ceil(values.size() * .5) - 1),
                "p95Ms",
                values.get((int) Math.ceil(values.size() * .95) - 1),
                "p99Ms",
                values.get((int) Math.ceil(values.size() * .99) - 1),
                "meanMs",
                mean,
                "stddevMs",
                Math.sqrt(values.stream()
                        .mapToDouble(value -> (value - mean) * (value - mean))
                        .average()
                        .orElseThrow()),
                "maxMs",
                values.get(values.size() - 1));
    }

    private static void stop(Process process) throws InterruptedException {
        if (process == null) return;
        process.destroy();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
