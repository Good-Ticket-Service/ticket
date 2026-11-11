package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/** 실제 FakePG DB 저장과 정상 승인·환불·정산 워커를 집계 OFF/B/C에 병행한다. */
@EnabledIfSystemProperty(named = "projection.financial", matches = "true")
class ProjectionFinancialCoexistenceTest {
    @TempDir
    Path temporary;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final AtomicLong ids = new AtomicLong();
    private final ConcurrentLinkedQueue<String> completed = new ConcurrentLinkedQueue<>();

    @Test
    void realApprovalsRefundsAndLedgerRemainCorrectWhileProjectionRuns() throws Exception {
        Path root = Path.of(System.getProperty("projection.output")).resolve("financial-" + System.currentTimeMillis());
        Files.createDirectories(root);
        for (String mode : System.getProperty("projection.modes", "OFF,B,C").split(",")) {
            Path output = root.resolve(mode);
            Files.createDirectories(output);
            var report = new LinkedHashMap<String, Object>();
            report.put("mode", mode);
            report.put("sourceTreeSha256", sourceHash());
            report.put("javaVersion", System.getProperty("java.version"));
            report.put("kafkaClientVersion", org.apache.kafka.common.utils.AppInfoParser.getVersion());
            report.put("brokerImage", "apache/kafka:4.3.1");
            report.put("databaseImage", "postgres:16");
            report.put("appHeapMiB", 512);
            report.put("enginePlusSinkHeapMiB", List.of("B", "C").contains(mode) ? 512 : 0);
            report.put("fakePgHeapMiB", 256);
            report.put("databaseCpuLimitEach", 2);
            report.put("databaseMemoryMiBEach", 2048);
            report.put("otherFinancialCronJobs", "disabled equally; existing ledger worker enabled");
            report.put("pgInMemoryApproval", false);
            report.put(
                    "sourceCollection",
                    "OFF".equals(mode) ? "benchmark-only no-op port" : "production source collection");
            report.put("financialWorker", "existing production scheduler; batch100, fixedDelay1000ms");
            Process app = null, engine = null, memory = null;
            ProjectionFinancialSampler observer = null;
            completed.clear();
            ids.set(0);
            try (var network = Network.newNetwork();
                    var postgres = database();
                    var pgDatabase = database().withNetwork(network).withNetworkAliases("financial-pg-db");
                    var kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"))
                            .withEnv("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1")
                            .withEnv("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1")
                            .withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1")
                            .withEnv("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0")
                            .withCreateContainerCmdModifier(command -> command.getHostConfig()
                                    .withNanoCPUs(2000000000L)
                                    .withMemory(2147483648L)
                                    .withMemorySwap(2147483648L))) {
                postgres.start();
                pgDatabase.start();
                kafka.start();
                try (var pg = new GenericContainer<>("eclipse-temurin:17-jre")
                                .withNetwork(network)
                                .withCopyFileToContainer(
                                        MountableFile.forHostPath(System.getProperty("fakePg.jar")), "/app.jar")
                                .withEnv(
                                        "SPRING_DATASOURCE_URL",
                                        "jdbc:postgresql://financial-pg-db:5432/" + pgDatabase.getDatabaseName())
                                .withEnv("SPRING_DATASOURCE_USERNAME", pgDatabase.getUsername())
                                .withEnv("SPRING_DATASOURCE_PASSWORD", pgDatabase.getPassword())
                                .withEnv("FAKE_PG_APPROVAL_IN_MEMORY", "false")
                                .withCommand("java", "-Xmx256m", "-jar", "/app.jar")
                                .withExposedPorts(8090)
                                .waitingFor(Wait.forHttp("/actuator/health")
                                        .forStatusCode(200)
                                        .withStartupTimeout(Duration.ofMinutes(2)));
                        var ds = ProjectionProcessMain.dataSource(
                                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                        var pgDs = ProjectionProcessMain.dataSource(
                                pgDatabase.getJdbcUrl(), pgDatabase.getUsername(), pgDatabase.getPassword())) {
                    pg.start();
                    var jdbc = new JdbcTemplate(ds);
                    var pgJdbc = new JdbcTemplate(pgDs);
                    try (var connection = ds.getConnection()) {
                        ScriptUtils.executeSqlScript(
                                connection, new FileSystemResource("../sql/payment-service/schema.sql"));
                    }
                    String pgBase = "http://" + pg.getHost() + ":" + pg.getMappedPort(8090);
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
                                    output.resolve("app-metrics.jsonl").toString(),
                                    pgBase),
                            output.resolve("app.log"));
                    Process appProcess = app;
                    await(() -> healthy(port), appProcess, Duration.ofMinutes(2));
                    if (List.of("B", "C").contains(mode)) {
                        engine = ProjectionProcessLauncher.start(
                                temporary,
                                postgres.getJdbcUrl(),
                                postgres.getUsername(),
                                postgres.getPassword(),
                                kafka.getBootstrapServers(),
                                temporary.resolve(mode + "-state"),
                                "financial." + UUID.randomUUID(),
                                output.resolve("engine.log"),
                                List.of(
                                        mode,
                                        "NONE",
                                        "unused",
                                        "0",
                                        "1000",
                                        output.resolve("engine-metrics.jsonl").toString()));
                        Process engineProcess = engine;
                        await(
                                () -> jdbc.queryForObject(
                                                "SELECT generation FROM p_settlement_projection_head WHERE id=1",
                                                Long.class)
                                        == 1,
                                engineProcess,
                                Duration.ofMinutes(2));
                    }
                    jdbc.execute("CREATE EXTENSION pg_stat_statements");
                    pgJdbc.execute("CREATE EXTENSION pg_stat_statements");
                    String monitor = System.getProperty("projection.monitor.script", "");
                    if (!monitor.isBlank()) {
                        var command = new ArrayList<>(List.of(
                                "python",
                                monitor,
                                output.resolve("process-memory.jsonl").toString(),
                                "api=" + app.pid(),
                                "generator=" + ProcessHandle.current().pid()));
                        if (engine != null) command.add("engine=" + engine.pid());
                        memory = new ProcessBuilder(command)
                                .redirectErrorStream(true)
                                .redirectOutput(output.resolve("monitor.log").toFile())
                                .start();
                    }
                    observer = new ProjectionFinancialSampler(jdbc, output.resolve("worker-progress.jsonl"));
                    String prefix = "ts6-fin-" + UUID.randomUUID().toString().substring(0, 8) + "-";
                    report.put(
                            "warmup",
                            phase(
                                    port,
                                    pgBase,
                                    prefix,
                                    Integer.getInteger("projection.financialWarmSeconds", 120),
                                    output,
                                    "warmup"));
                    jdbc.execute("SELECT pg_stat_statements_reset()");
                    pgJdbc.execute("SELECT pg_stat_statements_reset()");
                    report.put("serviceDatabaseBefore", statistics(jdbc));
                    report.put("pgDatabaseBefore", statistics(pgJdbc));
                    report.put("serviceContainerBefore", resources(postgres));
                    report.put("pgDatabaseContainerBefore", resources(pgDatabase));
                    report.put("brokerBefore", resources(kafka));
                    report.put("fakePgBefore", resources(pg));
                    report.put(
                            "workerPendingBefore",
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM p_settlement_jobs WHERE status<>'DONE'", Long.class));
                    String serviceWalBefore = jdbc.queryForObject("SELECT pg_current_wal_lsn()::text", String.class);
                    String pgWalBefore = pgJdbc.queryForObject("SELECT pg_current_wal_lsn()::text", String.class);
                    report.put(
                            "measurement",
                            phase(
                                    port,
                                    pgBase,
                                    prefix,
                                    Integer.getInteger("projection.financialSeconds", 300),
                                    output,
                                    "measurement"));
                    report.put(
                            "serviceWalBytes",
                            jdbc.queryForObject(
                                    "SELECT pg_wal_lsn_diff(pg_current_wal_lsn(),?::pg_lsn)",
                                    Long.class,
                                    serviceWalBefore));
                    report.put(
                            "pgWalBytes",
                            pgJdbc.queryForObject(
                                    "SELECT pg_wal_lsn_diff(pg_current_wal_lsn(),?::pg_lsn)", Long.class, pgWalBefore));
                    report.put("serviceDatabaseAfter", statistics(jdbc));
                    report.put("pgDatabaseAfter", statistics(pgJdbc));
                    report.put("serviceContainerAfter", resources(postgres));
                    report.put("pgDatabaseContainerAfter", resources(pgDatabase));
                    report.put("brokerAfter", resources(kafka));
                    report.put("fakePgAfter", resources(pg));
                    report.put("serviceStatements", statements(jdbc));
                    report.put("pgStatements", statements(pgJdbc));
                    report.put(
                            "workerPendingAfter",
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM p_settlement_jobs WHERE status<>'DONE'", Long.class));
                    long drainStarted = System.nanoTime();
                    await(
                            () -> jdbc.queryForObject(
                                            "SELECT count(*) FROM p_settlement_jobs WHERE status<>'DONE'", Long.class)
                                    == 0,
                            appProcess,
                            Duration.ofMinutes(5));
                    if ("PAUSED".equals(mode)) {
                        long collected =
                                jdbc.queryForObject("SELECT count(*) FROM p_settlement_projection_facts", Long.class);
                        long eligible = jdbc.queryForObject(
                                "SELECT count(*) FROM p_ledger_transactions WHERE source_type IN ('SALE','REFUND')",
                                Long.class);
                        assertThat(collected).isPositive().isEqualTo(eligible);
                        report.put("collectedWithRuntimeDisabled", collected);
                        engine = ProjectionProcessLauncher.start(
                                temporary,
                                postgres.getJdbcUrl(),
                                postgres.getUsername(),
                                postgres.getPassword(),
                                kafka.getBootstrapServers(),
                                temporary.resolve("paused-resume-state"),
                                "paused." + UUID.randomUUID(),
                                output.resolve("resume-engine.log"),
                                List.of(
                                        "C",
                                        "NONE",
                                        "unused",
                                        "0",
                                        "1000",
                                        output.resolve("resume-engine-metrics.jsonl")
                                                .toString()));
                        Process resumed = engine;
                        await(
                                () -> jdbc.queryForObject(
                                                "SELECT generation FROM p_settlement_projection_head WHERE id=1",
                                                Long.class)
                                        == 1,
                                resumed,
                                Duration.ofMinutes(2));
                    }
                    if (engine != null) {
                        Process engineProcess = engine;
                        await(
                                () -> jdbc.queryForObject(
                                                "SELECT (SELECT count(*) FROM p_ledger_transactions WHERE source_type IN ('SALE','REFUND'))-(SELECT count(*) FROM p_settlement_projection_applied WHERE build_id=(SELECT build_id FROM p_settlement_projection_head WHERE id=1))",
                                                Long.class)
                                        == 0,
                                engineProcess,
                                Duration.ofMinutes(5));
                    }
                    if (engine != null) {
                        Process engineProcess = engine;
                        await(
                                () -> ProjectionLoadDriver.keyDifferences(jdbc) == 0,
                                engineProcess,
                                Duration.ofSeconds(30));
                        report.put("independentPerKeyDifferences", 0);
                    }
                    report.put("drainMs", (System.nanoTime() - drainStarted) / 1_000_000.0);
                    report.put("payments", jdbc.queryForList("SELECT status,count(*) FROM p_payments GROUP BY status"));
                    report.put(
                            "pgTransactions",
                            pgJdbc.queryForList("SELECT status,count(*) FROM pg_transaction GROUP BY status"));
                    long payments = jdbc.queryForObject("SELECT count(*) FROM p_payments", Long.class);
                    long pgTransactions = pgJdbc.queryForObject("SELECT count(*) FROM pg_transaction", Long.class);
                    assertThat(pgTransactions).isEqualTo(payments);
                    long paid = jdbc.queryForObject(
                            "SELECT coalesce(sum(amount),0) FROM p_payments WHERE status='DONE'", Long.class);
                    long pgPaid = pgJdbc.queryForObject(
                            "SELECT coalesce(sum(amount),0) FROM pg_transaction WHERE status='DONE'", Long.class);
                    long clearing = jdbc.queryForObject(
                            "SELECT coalesce(sum(debit-credit),0) FROM p_ledger_entries WHERE account='PG_CLEARING'",
                            Long.class);
                    assertThat(clearing).isEqualTo(paid).isEqualTo(pgPaid);
                    long refund = jdbc.queryForObject(
                            "SELECT coalesce(sum(refund_amount),0) FROM p_refunds WHERE status='DONE'", Long.class);
                    long pgRefund = pgJdbc.queryForObject(
                            "SELECT coalesce(sum(canceled_amount),0) FROM pg_transaction WHERE status='CANCELED'",
                            Long.class);
                    assertThat(refund).isEqualTo(pgRefund);
                    assertThat(jdbc.queryForObject(
                                    "SELECT coalesce(sum(debit-credit),0) FROM p_ledger_entries", Long.class))
                            .isZero();
                    assertThat(jdbc.queryForObject(
                                    "SELECT count(*) FROM p_settlement_items WHERE kind='SALE'", Long.class))
                            .isEqualTo(payments);
                    report.put("paymentsCount", payments);
                    report.put("pgTransactionsCount", pgTransactions);
                    report.put("netPgClearing", clearing);
                    report.put("completedRefundAmount", refund);
                    report.put("ledgerDifference", 0);
                    report.put(
                            "sourceFacts",
                            jdbc.queryForObject("SELECT count(*) FROM p_settlement_projection_facts", Long.class));
                    assertThat(payments).isPositive();
                    assertThat(jdbc.queryForObject(
                                    "SELECT count(*) FROM p_payments WHERE status NOT IN ('DONE','CANCELED')",
                                    Long.class))
                            .isZero();
                    assertThat(((Number) ((Map<?, ?>) report.get("warmup")).get("failedFlows")).longValue())
                            .isZero();
                    assertThat(((Number) ((Map<?, ?>) report.get("measurement")).get("failedFlows")).longValue())
                            .isZero();
                    report.put(
                            "workerLatencyScope",
                            "all completed jobs including warmup; application KST timestamps; not PG or HTTP approval duration");
                    report.put("workerLatency", jdbc.queryForList("""
                            SELECT reason,count(*) AS jobs,
                              percentile_cont(0.50) WITHIN GROUP(ORDER BY extract(epoch FROM(done_at-created_at))*1000) AS p50_ms,
                              percentile_cont(0.95) WITHIN GROUP(ORDER BY extract(epoch FROM(done_at-created_at))*1000) AS p95_ms,
                              percentile_cont(0.99) WITHIN GROUP(ORDER BY extract(epoch FROM(done_at-created_at))*1000) AS p99_ms,
                              max(extract(epoch FROM(done_at-created_at))*1000) AS max_ms
                            FROM p_settlement_jobs WHERE status='DONE' GROUP BY reason
                            """));
                    report.put(
                            "financialProjectionLatencyScope",
                            "applied has no timestamp; observe unapplied count/oldest age every second; HTTP freshness distribution belongs to the synthetic comparison, not this financial run");
                    observer.close();
                    observer = null;
                    report.put("passed", true);
                } finally {
                    try {
                        if (observer != null) observer.close();
                    } finally {
                        stop(memory);
                        stop(engine);
                        stop(app);
                    }
                }
            } finally {
                json.writerWithDefaultPrettyPrinter()
                        .writeValue(output.resolve("report.json").toFile(), report);
            }
        }
    }

    private static String sourceHash() throws Exception {
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        try (var files = Files.walk(Path.of("src/main"))) {
            for (Path path : files.filter(Files::isRegularFile).sorted().toList()) {
                digest.update(path.toString().replace('\\', '/').getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update(Files.readAllBytes(path));
            }
        }
        for (String name : List.of(
                "ProjectionFinancialCoexistenceTest",
                "ProjectionFinancialSampler",
                "ProjectionBenchmarkMain",
                "ProjectionProcessMain",
                "ProjectionProcessLauncher",
                "ProjectionJvmSampler",
                "ProjectionKafkaIntegrationTest",
                "ProjectionLoadDriver")) {
            digest.update(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update(Files.readAllBytes(
                    Path.of("src/test/java/com/sparta/ticket/payment/infrastructure/persistence/settlement/" + name
                            + ".java")));
        }
        digest.update(Files.readAllBytes(Path.of("../sql/payment-service/schema.sql")));
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private Map<String, Object> phase(int port, String pg, String prefix, int seconds, Path output, String name)
            throws Exception {
        int rate = Integer.getInteger("projection.financialRate", 100);
        if (seconds < 1 || rate < 1) throw new IllegalArgumentException("금융 부하 설정 오류");
        var records = new ConcurrentLinkedQueue<Map<String, Object>>();
        var reads = new ConcurrentLinkedQueue<Map<String, Object>>();
        var executor = new ThreadPoolExecutor(64, 64, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1000));
        var rejected = new AtomicLong();
        long scheduled = 0;
        long start = System.nanoTime(),
                end = start + Duration.ofSeconds(seconds).toNanos();
        long epoch = System.currentTimeMillis();
        var readSlots = new AtomicLong();
        var reader = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        reader.scheduleAtFixedRate(
                () -> {
                    if (System.nanoTime() >= end) return;
                    readSlots.incrementAndGet();
                    String order = completed.poll();
                    if (order != null) {
                        completed.add(order);
                        try {
                            executor.execute(() -> {
                                long readStart = System.nanoTime();
                                boolean success = false;
                                try {
                                    success = !request(port, "/internal/payments/" + order, null)
                                            .path("status")
                                            .isMissingNode();
                                } catch (Exception exception) {
                                    success = false;
                                }
                                reads.add(Map.of(
                                        "success",
                                        success,
                                        "latencyMs",
                                        (System.nanoTime() - readStart) / 1_000_000.0));
                            });
                        } catch (java.util.concurrent.RejectedExecutionException exception) {
                            rejected.incrementAndGet();
                        }
                    }
                },
                0,
                50,
                TimeUnit.MILLISECONDS);
        try {
            for (long planned = start; planned < end; planned += 1_000_000_000L / rate) {
                while (System.nanoTime() < planned)
                    LockSupport.parkNanos(Math.min(1_000_000, planned - System.nanoTime()));
                if (System.nanoTime() >= end) break;
                long target = planned;
                long id = ids.incrementAndGet();
                scheduled++;
                try {
                    executor.execute(() -> flow(port, pg, prefix + id, id, target, records));
                } catch (java.util.concurrent.RejectedExecutionException exception) {
                    rejected.incrementAndGet();
                }
            }
            while (System.nanoTime() < end) LockSupport.parkNanos(Math.min(1_000_000, end - System.nanoTime()));
        } finally {
            reader.shutdownNow();
            assertThat(reader.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
                throw new AssertionError("금융 발생기 종료 시간 초과");
            }
            Files.writeString(output.resolve(name + "-flows.json"), json.writeValueAsString(records));
            Files.writeString(output.resolve(name + "-reads.json"), json.writeValueAsString(reads));
        }
        long failed = records.stream()
                .filter(r -> !Boolean.TRUE.equals(r.get("success")))
                .count();
        var summary = new LinkedHashMap<String, Object>();
        summary.put("startEpochMs", epoch);
        summary.put("endEpochMs", System.currentTimeMillis());
        summary.put("elapsedIncludingFinalRequestsSeconds", (System.nanoTime() - start) / 1_000_000_000.0);
        summary.put("targetReadsPerSecond", 20);
        summary.put("scheduledReadSlots", readSlots.get());
        summary.put("plannedSeconds", seconds);
        summary.put("targetFlowsPerSecond", rate);
        summary.put("scheduledFlows", scheduled);
        summary.put("notScheduledFlows", Math.max(0, (long) seconds * rate - scheduled));
        summary.put("completedFlows", records.size());
        summary.put("rejectedTasks", rejected.get());
        summary.put("failedFlows", failed);
        summary.put("paymentReads", reads.size());
        summary.put(
                "failedReads",
                reads.stream()
                        .filter(r -> !Boolean.TRUE.equals(r.get("success")))
                        .count());
        summary.put("flowLatencyAllAttempts", percentiles(records, "flowMs"));
        summary.put(
                "flowLatencySuccessful",
                percentiles(
                        records.stream()
                                .filter(r -> Boolean.TRUE.equals(r.get("success")))
                                .toList(),
                        "flowMs"));
        summary.put(
                "confirmLatency",
                percentiles(
                        records.stream().filter(r -> r.containsKey("confirmMs")).toList(), "confirmMs"));
        summary.put(
                "refundLatency",
                percentiles(
                        records.stream().filter(r -> r.containsKey("refundMs")).toList(), "refundMs"));
        summary.put(
                "confirmLatencySuccessful",
                percentiles(
                        records.stream()
                                .filter(r -> "DONE".equals(r.get("confirmStatus")))
                                .toList(),
                        "confirmMs"));
        summary.put(
                "refundLatencySuccessful",
                percentiles(
                        records.stream()
                                .filter(r -> "DONE".equals(r.get("refundStatus")))
                                .toList(),
                        "refundMs"));
        summary.put("generatorDelay", percentiles(records, "queueDelayMs"));
        summary.put("readLatency", percentiles(reads, "latencyMs"));
        return summary;
    }

    private void flow(
            int port,
            String pg,
            String order,
            long id,
            long planned,
            ConcurrentLinkedQueue<Map<String, Object>> records) {
        long start = System.nanoTime();
        long confirmStarted = 0;
        long refundStarted = 0;
        var result = new LinkedHashMap<String, Object>();
        result.put("orderId", order);
        result.put("queueDelayMs", (start - planned) / 1_000_000.0);
        result.put("success", false);
        result.put("stage", "PREPARE");
        try {
            var now = OffsetDateTime.now(ZoneId.of("Asia/Seoul"));
            var checkout = new LinkedHashMap<String, Object>();
            checkout.put("orderId", order);
            checkout.put("reservationId", id);
            checkout.put("userId", 1);
            checkout.put("sellerId", 1 + id % 1000);
            checkout.put("performanceId", 1);
            checkout.put("scheduleId", 1 + id % 10000);
            checkout.put("totalPrice", 10000);
            checkout.put("discountAmount", 1000);
            checkout.put("pgProvider", "TOSS");
            checkout.put("expiresAt", now.plusMinutes(10).toString());
            checkout.put("scheduleStartAt", now.plusDays(2).toString());
            request(port, "/internal/payments", checkout);
            result.put("stage", "PG_CHECKOUT");
            JsonNode authorized = send(pg + "/fake/toss/checkout", Map.of("orderId", order, "amount", 9000));
            result.put("stage", "CONFIRM");
            long confirm = System.nanoTime();
            confirmStarted = confirm;
            JsonNode approved = request(
                    port,
                    "/internal/payments/" + order + "/confirm",
                    Map.of("pgAuthToken", authorized.path("paymentKey").asText(), "amount", 9000));
            result.put("confirmMs", (System.nanoTime() - confirm) / 1_000_000.0);
            result.put("confirmStatus", approved.path("status").asText());
            result.put("confirmFailureCode", approved.path("failureCode").asText());
            if (!"DONE".equals(approved.path("status").asText())) throw new IllegalStateException("정상 승인이 DONE이 아니다");
            completed.add(order);
            if (id % 20 == 0) {
                result.put("stage", "REFUND");
                long refund = System.nanoTime();
                refundStarted = refund;
                JsonNode canceled =
                        request(port, "/internal/payments/" + order + "/refund", Map.of("reason", "USER_CANCEL"));
                result.put("refundMs", (System.nanoTime() - refund) / 1_000_000.0);
                result.put("refundStatus", canceled.path("refundStatus").asText());
                if (!"DONE".equals(canceled.path("refundStatus").asText()))
                    throw new IllegalStateException("정상 환불이 DONE이 아니다");
            }
            result.put("success", true);
            result.put("stage", "COMPLETE");
        } catch (Exception exception) {
            result.put("error", exception.getClass().getSimpleName());
            if (exception instanceof UnexpectedFinancialHttp httpFailure) result.put("httpStatus", httpFailure.status);
        } finally {
            if (confirmStarted != 0 && !result.containsKey("confirmMs"))
                result.put("confirmMs", (System.nanoTime() - confirmStarted) / 1_000_000.0);
            if (refundStarted != 0 && !result.containsKey("refundMs"))
                result.put("refundMs", (System.nanoTime() - refundStarted) / 1_000_000.0);
            result.put("flowMs", (System.nanoTime() - start) / 1_000_000.0);
            records.add(result);
        }
    }

    private JsonNode request(int port, String path, Object body) throws Exception {
        return send("http://127.0.0.1:" + port + path, body);
    }

    private JsonNode send(String url, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15));
        if (body == null) builder.GET();
        else
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new UnexpectedFinancialHttp(response.statusCode());
        return json.readTree(response.body());
    }

    private static final class UnexpectedFinancialHttp extends RuntimeException {
        private final int status;

        private UnexpectedFinancialHttp(int status) {
            super("금융 HTTP 응답 실패");
            this.status = status;
        }
    }

    private boolean healthy(int port) {
        try {
            return "UP"
                    .equals(request(port, "/actuator/health", null)
                            .path("status")
                            .asText());
        } catch (Exception exception) {
            return false;
        }
    }

    private static Map<String, Object> percentiles(Iterable<Map<String, Object>> records, String key) {
        var values = new ArrayList<Double>();
        records.forEach(record -> values.add(((Number) record.get(key)).doubleValue()));
        if (values.isEmpty()) return Map.of("count", 0);
        double[] sorted = values.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(sorted);
        return Map.of(
                "count",
                sorted.length,
                "p50Ms",
                sorted[(int) Math.ceil(sorted.length * .5) - 1],
                "p95Ms",
                sorted[(int) Math.ceil(sorted.length * .95) - 1],
                "p99Ms",
                sorted[(int) Math.ceil(sorted.length * .99) - 1],
                "maxMs",
                sorted[sorted.length - 1],
                "meanMs",
                Arrays.stream(sorted).average().orElseThrow());
    }

    private static Map<String, Object> statistics(JdbcTemplate jdbc) {
        return jdbc.queryForMap(
                "SELECT blks_read,blks_hit,temp_bytes,deadlocks,blk_read_time,blk_write_time FROM pg_stat_database WHERE datname=current_database()");
    }

    private static List<Map<String, Object>> statements(JdbcTemplate jdbc) {
        return jdbc.queryForList(
                "SELECT query,calls,rows,total_exec_time,shared_blks_hit,shared_blks_read,wal_bytes,wal_records,wal_fpi FROM pg_stat_statements ORDER BY total_exec_time DESC");
    }

    private static Map<String, String> resources(GenericContainer<?> container) throws Exception {
        return Map.of(
                "cpu",
                container.execInContainer("cat", "/sys/fs/cgroup/cpu.stat").getStdout(),
                "memoryCurrent",
                container
                        .execInContainer("cat", "/sys/fs/cgroup/memory.current")
                        .getStdout(),
                "io",
                container.execInContainer("cat", "/sys/fs/cgroup/io.stat").getStdout());
    }

    private static PostgreSQLContainer<?> database() {
        return new PostgreSQLContainer<>("postgres:16")
                .withCommand(
                        "postgres", "-c", "shared_preload_libraries=pg_stat_statements", "-c", "track_io_timing=on")
                .withCreateContainerCmdModifier(command -> command.getHostConfig()
                        .withNanoCPUs(2000000000L)
                        .withMemory(2147483648L)
                        .withMemorySwap(2147483648L));
    }

    private static void await(BooleanSupplier done, Process process, Duration limit) throws InterruptedException {
        long deadline = System.nanoTime() + limit.toNanos();
        while (!done.getAsBoolean() && System.nanoTime() < deadline) {
            assertThat(process.isAlive()).as("금융 검증 JVM 생존").isTrue();
            Thread.sleep(250);
        }
        assertThat(done.getAsBoolean()).as("제한 시간 내 금융 반영 완료").isTrue();
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
