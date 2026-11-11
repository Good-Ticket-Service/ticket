package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.common.auth.UserHeaders;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/** 비교 도구의 실제 HTTP 계약·독립 원천 정답을 먼저 검증한다. 정상 부하 측정과 구분한다. */
@EnabledIfSystemProperty(named = "projection.compare", matches = "true")
class ProjectionComparisonTest {
    @TempDir
    Path temporary;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @Test
    void isolatedSpringHttpAndEngineProcessesUseTheSameBusinessAnswer() throws Exception {
        int rows = Integer.getInteger("projection.rows", 100000);
        Path output = Path.of(System.getProperty("projection.output")).resolve("wiring-" + System.currentTimeMillis());
        Files.createDirectories(output);
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
                                .withMemorySwap(2147483648L));
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
            try (var admin = ProjectionProcessMain.dataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                new JdbcTemplate(admin).execute("CREATE EXTENSION pg_stat_statements");
            }
            kafka.start();
            var prefixes = new java.util.HashMap<String, String>();
            var roundHistories = new java.util.HashMap<Integer, Integer>();
            var endingHistories = new java.util.HashMap<String, Integer>();
            int rounds = Integer.getInteger("projection.rounds", 1);
            if (rounds < 1 || rounds > 3) throw new IllegalArgumentException("비교 반복은1~3회다");
            for (int round = 0; round < rounds; round++) {
                int alignedHistory = endingHistories.values().stream()
                        .mapToInt(Integer::intValue)
                        .max()
                        .orElse(rows);
                var modes = new java.util.ArrayList<>(
                        List.of(System.getProperty("projection.modes", "A0,B,C").split(",")));
                java.util.Collections.rotate(modes, -round);
                for (String mode : modes) {
                    boolean initialize = round == 0;
                    Path trial = rounds == 1
                            ? output.resolve(mode)
                            : output.resolve("round-" + round).resolve(mode);
                    Files.createDirectories(trial);
                    Process api = null, engine = null;
                    var report = new LinkedHashMap<String, Object>();
                    report.put("mode", mode);
                    report.put("rows", rows);
                    report.put("dataProfile", ProjectionFixture.PROFILE);
                    report.put("repetition", Integer.getInteger("projection.repeat", 0) + round);
                    report.put("initialHistoryRows", rows);
                    report.put(
                            "repetitionScope",
                            rounds == 1
                                    ? "single fresh fixture"
                                    : "continued append-only history; fresh API/engine JVM per rotated round; not independent fresh fixtures");
                    report.put("engineStartupKind", initialize ? "fresh-build" : "resume-existing-build");
                    report.put("javaVersion", System.getProperty("java.version"));
                    report.put("kafkaClientVersion", org.apache.kafka.common.utils.AppInfoParser.getVersion());
                    report.put("apiHeapMiB", 512);
                    report.put("enginePlusSinkHeapMiB", "A0".equals(mode) ? 0 : 512);
                    report.put("databaseCpuLimit", 2);
                    report.put("databaseMemoryMiB", 2048);
                    report.put("brokerCpuLimit", 2);
                    report.put("brokerMemoryMiB", 2048);
                    report.put("partitions", 2);
                    report.put("streamThreads", 1);
                    report.put("engineTickMs", 1000);
                    report.put("claimLeaseMillis", 2000);
                    report.put("sendTimeoutMillis", 5000);
                    report.put("batchSize", 500);
                    var defaults =
                            new com.sparta.ticket.payment.infrastructure.settlement.projection.ProjectionProperties();
                    report.put("maxPublishBatches", defaults.getMaxPublishBatches());
                    report.put("maxPublishRunMillis", defaults.getMaxPublishRunMillis());
                    report.put("processingGuarantee", "exactly_once_v2");
                    report.put("streamsCommitIntervalMillis", 100);
                    report.put("streamsCacheMiB", 8);
                    report.put(
                            "queryOwners",
                            "uniform random seed 334; ten freshness probe slots per second replace ordinary requests");
                    report.put("sourceTreeSha256", sourceHash());
                    report.put(
                            "profile",
                            Boolean.getBoolean("projection.load")
                                    ? "open-loop mixed source and HTTP; isolated JVMs"
                                    : "HTTP wiring only; no steady-load performance claim");
                    String schema = "compare_" + mode.toLowerCase(java.util.Locale.ROOT);
                    try (var setup = ProjectionProcessMain.dataSource(
                            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                        new JdbcTemplate(setup).execute("CREATE SCHEMA IF NOT EXISTS " + schema);
                    }
                    String url = postgres.getJdbcUrl() + (postgres.getJdbcUrl().contains("?") ? "&" : "?")
                            + "currentSchema=" + schema;
                    try (var ds =
                            ProjectionProcessMain.dataSource(url, postgres.getUsername(), postgres.getPassword())) {
                        if (initialize)
                            try (var connection = ds.getConnection()) {
                                ScriptUtils.executeSqlScript(
                                        connection, new FileSystemResource("../sql/payment-service/schema.sql"));
                            }
                        var jdbc = new JdbcTemplate(ds);
                        int beforeAlignment = initialize
                                ? rows
                                : jdbc.queryForObject("SELECT count(*) FROM p_ledger_transactions", Integer.class);
                        long alignmentStarted = System.nanoTime();
                        if (!initialize && beforeAlignment < alignedHistory) {
                            new TransactionTemplate(new DataSourceTransactionManager(ds))
                                    .executeWithoutResult(status -> {
                                        long floor = jdbc.queryForObject(
                                                "SELECT coalesce(max(id),0) FROM p_ledger_transactions", Long.class);
                                        ProjectionFixture.seed(
                                                jdbc,
                                                beforeAlignment * 95 / 100,
                                                alignedHistory - beforeAlignment,
                                                false,
                                                ProjectionFixture.Distribution.UNIFORM);
                                        if (!"A0".equals(mode)) {
                                            var source = new com.sparta.ticket.payment.infrastructure.persistence
                                                    .settlement.projection.SettlementSourceJdbc(jdbc);
                                            var journal = new com.sparta.ticket.payment.infrastructure.persistence
                                                    .settlement.projection.SettlementFactJdbc(jdbc, source);
                                            source.facts(" AND t.id>?", floor).forEach(journal::save);
                                        }
                                    });
                        }
                        report.put("historyBeforeAlignment", beforeAlignment);
                        report.put("alignmentAddedFacts", initialize ? 0 : alignedHistory - beforeAlignment);
                        report.put("alignmentMs", (System.nanoTime() - alignmentStarted) / 1_000_000.0);
                        report.put(
                                "alignmentScope",
                                "fixture preparation before startup; prior raw supply shortfalls remain unchanged; excluded from load throughput");
                        long seed = System.nanoTime();
                        if (initialize)
                            new TransactionTemplate(new DataSourceTransactionManager(ds))
                                    .executeWithoutResult(status -> ProjectionFixture.seed(
                                            jdbc,
                                            0,
                                            rows,
                                            true,
                                            ProjectionFixture.Distribution.valueOf(
                                                    System.getProperty("projection.distribution", "UNIFORM"))));
                        jdbc.execute("ANALYZE");
                        report.put("seedMs", initialize ? (System.nanoTime() - seed) / 1000000 : 0);
                        int currentRows =
                                jdbc.queryForObject("SELECT count(*) FROM p_ledger_transactions", Integer.class);
                        report.put("rows", currentRows);
                        Integer cohort = roundHistories.putIfAbsent(round, currentRows);
                        if (cohort != null)
                            assertThat(currentRows)
                                    .as("같은 round의 모든 mode는 같은 원천 이력 수")
                                    .isEqualTo(cohort);
                        report.put(
                                "actualInput",
                                jdbc.queryForMap(
                                        "SELECT count(*) AS facts,count(DISTINCT seller_id) AS sellers,count(*) FILTER(WHERE source_type='SALE') AS sales,count(*) FILTER(WHERE source_type='REFUND') AS refunds FROM p_ledger_transactions"));
                        report.put(
                                "activeKeys",
                                jdbc.queryForObject(
                                        "SELECT count(*) FROM (SELECT t.seller_id,p.schedule_id,t.occurred_at::date FROM p_ledger_transactions t JOIN p_payments p ON p.id=t.payment_id GROUP BY t.seller_id,p.schedule_id,t.occurred_at::date) keys",
                                        Long.class));
                        report.put(
                                "schedules",
                                jdbc.queryForObject("SELECT count(DISTINCT schedule_id) FROM p_payments", Long.class));
                        if (initialize
                                && rows >= 100000
                                && !"HOT_KEY".equals(System.getProperty("projection.distribution", "UNIFORM"))) {
                            assertThat(report.get("activeKeys"))
                                    .as("이력 크기와 독립인 과거 활성 집계 키")
                                    .isEqualTo(10500L);
                            assertThat(report.get("schedules")).isEqualTo(10000L);
                        }
                        jdbc.execute("SELECT public.pg_stat_statements_reset()");
                        report.put(
                                "bootstrapStatementScope",
                                "reset after seed/analyze/input metadata; startup and initial correctness checks included; preceding modes excluded");
                        int port;
                        try (var socket = new ServerSocket(0)) {
                            port = socket.getLocalPort();
                        }
                        long started = System.nanoTime();
                        api = ProjectionProcessLauncher.startMain(
                                temporary,
                                ProjectionBenchmarkMain.class.getName(),
                                List.of(
                                        url,
                                        postgres.getUsername(),
                                        postgres.getPassword(),
                                        Integer.toString(port),
                                        mode,
                                        trial.resolve("api-metrics.jsonl").toString()),
                                trial.resolve("api.log"));
                        Process apiProcess = api;
                        await(() -> response(port, 2) != null, apiProcess, Duration.ofMinutes(2));
                        report.put("apiStartupMs", (System.nanoTime() - started) / 1000000);
                        if (!"A0".equals(mode)) {
                            long bootstrap = System.nanoTime();
                            engine = ProjectionProcessLauncher.start(
                                    temporary,
                                    url,
                                    postgres.getUsername(),
                                    postgres.getPassword(),
                                    kafka.getBootstrapServers(),
                                    temporary.resolve(mode + "-state"),
                                    prefixes.computeIfAbsent(mode, ignored -> "compare." + UUID.randomUUID()),
                                    trial.resolve("engine.log"),
                                    List.of(
                                            mode,
                                            "NONE",
                                            "unused",
                                            "0",
                                            "1000",
                                            trial.resolve("engine-metrics.jsonl")
                                                    .toString()));
                            Process engineProcess = engine;
                            await(
                                    () -> jdbc.queryForObject(
                                                    "SELECT generation FROM p_settlement_projection_head WHERE id=1",
                                                    Long.class)
                                            == 1,
                                    engineProcess,
                                    Duration.ofMinutes(90));
                            report.put("engineBootstrapMs", (System.nanoTime() - bootstrap) / 1000000);
                            await(
                                    () -> {
                                        JsonNode ready = response(port, 2);
                                        return ready != null
                                                && "READY"
                                                        .equals(ready.path("data")
                                                                .path("status")
                                                                .asText());
                                    },
                                    engineProcess,
                                    Duration.ofSeconds(30));
                            report.put("engineHealthyObservedMs", (System.nanoTime() - bootstrap) / 1000000);
                        }
                        for (long seller : List.of(1L, 2L, 20L, 39L, 110L)) {
                            JsonNode actual = response(port, seller);
                            assertThat(actual).isNotNull();
                            assertThat(actual.path("data").path("status").asText())
                                    .isEqualTo("READY");
                            var expected = new long[4];
                            for (int id = 1; id <= currentRows * 95 / 100; id++)
                                if (ProjectionLoadDriver.seller(id) == seller) {
                                    long amount = id % 2 == 0 ? 8680 : 8900;
                                    expected[0]++;
                                    expected[1] += amount;
                                    if (id % 19 == 0) {
                                        expected[2]++;
                                        expected[3] += amount;
                                    }
                                }
                            var totals = actual.path("data").path("totals");
                            assertThat(totals.path("saleCount").asLong()).isEqualTo(expected[0]);
                            assertThat(totals.path("saleSellerAmount").asLong()).isEqualTo(expected[1]);
                            assertThat(totals.path("refundCount").asLong()).isEqualTo(expected[2]);
                            assertThat(totals.path("refundSellerAmount").asLong())
                                    .isEqualTo(expected[3]);
                            assertThat(totals.path("netSellerAmount").asLong()).isEqualTo(expected[1] - expected[3]);
                        }
                        if (Boolean.getBoolean("projection.load")) {
                            var driver =
                                    new ProjectionLoadDriver(jdbc, mode, port, currentRows, trial, postgres, kafka);
                            report.put("load", driver.run(api, engine));
                        }
                        if (round == rounds - 1 && Boolean.getBoolean("projection.explore"))
                            explore(jdbc, mode, port, trial, postgres, kafka, api, engine, report);
                        report.put(
                                "financialDifference",
                                jdbc.queryForObject("SELECT sum(debit)-sum(credit) FROM p_ledger_entries", Long.class));
                        assertThat(jdbc.queryForObject(
                                        "SELECT sum(debit)-sum(credit) FROM p_ledger_entries", Long.class))
                                .isZero();
                        report.put("postgres", jdbc.queryForObject("SELECT version()", String.class));
                        report.put("relationsAfterAllPhases", jdbc.queryForList("""
                            SELECT relname,pg_total_relation_size(relid) AS total_bytes,
                              pg_relation_size(relid) AS heap_bytes,pg_indexes_size(relid) AS index_bytes
                            FROM pg_stat_user_tables WHERE schemaname=current_schema() ORDER BY relname
                            """));
                        report.put("brokerTopicStorageAfterAllPhases", topicStorage(kafka.getBootstrapServers()));
                        if ("C".equals(mode))
                            report.put("localStateBytes", directoryBytes(temporary.resolve(mode + "-state")));
                        if (!"A0".equals(mode)) report.put("healthPlansAfterLoad", healthPlans(jdbc));
                        report.put("passed", true);
                        endingHistories.put(
                                mode, jdbc.queryForObject("SELECT count(*) FROM p_ledger_transactions", Integer.class));
                    } finally {
                        stop(engine);
                        stop(api);
                        json.writerWithDefaultPrettyPrinter()
                                .writeValue(trial.resolve("report.json").toFile(), report);
                    }
                }
            }
        }
    }

    /** 측정 창 뒤의 seller 1 진단이다. API 실행 비용은 별도의 실제 pg_stat_statements로 비교한다. */
    private Map<String, Object> healthPlans(JdbcTemplate jdbc) throws Exception {
        var result = new LinkedHashMap<String, Object>();
        result.put(
                "scope",
                "post-load custom plan for synthetic seller 1; excluded from measured SQL/resource window; not every pooled API plan");
        var head = jdbc.queryForMap("SELECT h.build_id,b.fanout_snapshot FROM p_settlement_projection_head h "
                + "JOIN p_settlement_projection_builds b ON b.id=h.build_id WHERE h.id=1");
        UUID build = (UUID) head.get("build_id");
        var from = java.time.LocalDate.of(2026, 10, 11);
        var to = java.time.LocalDate.of(2026, 11, 10);
        for (String predicate : List.of("published_at IS NULL", "NOT applied")) {
            String plan = jdbc.queryForObject(
                    "EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) "
                            + "SELECT count(*) FROM p_settlement_projection_deliveries WHERE build_id=? "
                            + "AND seller_id=? AND business_date BETWEEN ? AND ? AND " + predicate,
                    String.class,
                    build,
                    1L,
                    from,
                    to);
            result.put(predicate, json.readTree(plan));
        }
        String snapshot = (String) head.get("fanout_snapshot");
        if (snapshot != null) {
            String plan = jdbc.queryForObject("""
                EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON)
                WITH recent AS MATERIALIZED (
                  SELECT fact_key,seller_id,schedule_id,business_date FROM p_settlement_projection_facts
                  WHERE writer_xid>=pg_snapshot_xmin(?::pg_snapshot)
                    AND NOT pg_visible_in_snapshot(writer_xid,?::pg_snapshot)
                ) SELECT count(*) FROM recent f WHERE f.seller_id=? AND f.business_date BETWEEN ? AND ?
                  AND NOT EXISTS(SELECT 1 FROM p_settlement_projection_deliveries d WHERE d.build_id=? AND d.fact_key=f.fact_key)
                """, String.class, snapshot, snapshot, 1L, from, to, build);
            result.put("not_fanned_out", json.readTree(plan));
        }
        return result;
    }

    private static long directoryBytes(Path directory) throws Exception {
        try (var files = Files.walk(directory)) {
            long total = 0;
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                try {
                    total += Files.size(path);
                } catch (java.nio.file.NoSuchFileException exception) {
                    // Note: RocksDB가 관측 중 정리한 파일은 크기 합계에서 제외한다.
                    continue;
                }
            }
            return total;
        }
    }

    private void explore(
            JdbcTemplate jdbc,
            String mode,
            int port,
            Path trial,
            PostgreSQLContainer<?> postgres,
            KafkaContainer kafka,
            Process api,
            Process engine,
            LinkedHashMap<String, Object> parent)
            throws Exception {
        int seconds = Integer.getInteger("projection.exploreSeconds", 180);
        var profiles = List.of(
                new LoadProfile("rate-100", 100, 50, "UNIFORM", seconds),
                new LoadProfile("rate-250", 250, 50, "UNIFORM", seconds),
                new LoadProfile("rate-500", 500, 50, "UNIFORM", seconds),
                new LoadProfile("rate-1000", 1000, 50, "UNIFORM", seconds),
                new LoadProfile("query-100", 100, 100, "UNIFORM", seconds),
                new LoadProfile("query-500", 100, 500, "UNIFORM", seconds),
                new LoadProfile("seller-skew", 100, 50, "SELLER_SKEW", seconds),
                new LoadProfile("hot-key", 100, 50, "HOT_KEY", seconds),
                new LoadProfile("burst", 100, 50, "UNIFORM", 480));
        var saved = new LinkedHashMap<String, String>();
        for (String key :
                List.of("sourceRate", "queryRate", "distribution", "warmSeconds", "measureSeconds", "scenario"))
            saved.put(key, System.getProperty("projection." + key));
        var completed = new java.util.ArrayList<String>();
        try {
            int firstLimit = -1;
            for (int index = 0; index < profiles.size(); index++) {
                LoadProfile profile = profiles.get(index);
                var measurement = exploreOne(jdbc, mode, port, trial, postgres, kafka, api, engine, parent, profile);
                completed.add(profile.name());
                if (index < 8 && firstLimit < 0 && limitObserved(measurement)) firstLimit = index;
            }
            // Note: 검증한 범위 밖 최대 처리량을 추측하지 않는다. 최초 한계의 아래/위 두 점만 장시간 재확인한다.
            if (firstLimit >= 0) {
                int neighbor = switch (firstLimit) {
                    case 4, 6, 7 -> 0;
                    case 5 -> 4;
                    default -> Math.max(0, firstLimit - 1);
                };
                for (int index : new java.util.LinkedHashSet<>(List.of(neighbor, firstLimit))) {
                    LoadProfile shortProfile = profiles.get(index);
                    LoadProfile longProfile = new LoadProfile(
                            "confirm-" + shortProfile.name(),
                            shortProfile.rate(),
                            shortProfile.queries(),
                            shortProfile.distribution(),
                            600);
                    exploreOne(jdbc, mode, port, trial, postgres, kafka, api, engine, parent, longProfile);
                    completed.add(longProfile.name());
                }
            }
            parent.put("explorationCompleted", completed);
            parent.put("firstExplorationLimitIndex", firstLimit);
            parent.put(
                    "explorationHistoryScope",
                    "same initial fixture; sequential profiles accumulate source history; actual history retained per profile; not independent repetitions");
        } finally {
            saved.forEach((key, value) -> {
                if (value == null) System.clearProperty("projection." + key);
                else System.setProperty("projection." + key, value);
            });
        }
    }

    private Map<?, ?> exploreOne(
            JdbcTemplate jdbc,
            String mode,
            int port,
            Path trial,
            PostgreSQLContainer<?> postgres,
            KafkaContainer kafka,
            Process api,
            Process engine,
            LinkedHashMap<String, Object> parent,
            LoadProfile profile)
            throws Exception {
        Path directory = trial.resolve(profile.name());
        Files.createDirectories(directory);
        System.setProperty("projection.sourceRate", Integer.toString(profile.rate()));
        System.setProperty("projection.queryRate", Integer.toString(profile.queries()));
        System.setProperty("projection.distribution", profile.distribution());
        System.setProperty("projection.warmSeconds", "30");
        System.setProperty("projection.measureSeconds", Integer.toString(profile.seconds()));
        System.setProperty("projection.scenario", "burst".equals(profile.name()) ? "BURST" : "STEADY");
        int history = jdbc.queryForObject("SELECT count(*) FROM p_ledger_transactions", Integer.class);
        var report = new LinkedHashMap<String, Object>(parent);
        report.remove("load");
        report.put("rows", history);
        report.put("initialHistoryRows", parent.get("rows"));
        report.put("explorationProfile", profile.name());
        // Note: JVM 지표는 부모 디렉터리에 연속 수집한다. profile의 epoch 경계로 잘라 비교한다.
        report.put("processMetricsDirectory", "..");
        try {
            var load = new ProjectionLoadDriver(jdbc, mode, port, history, directory, postgres, kafka).run(api, engine);
            report.put("load", load);
            long difference = jdbc.queryForObject("SELECT sum(debit)-sum(credit) FROM p_ledger_entries", Long.class);
            report.put("financialDifference", difference);
            assertThat(difference).isZero();
            report.put("passed", true);
            return (Map<?, ?>) load.get("measurement");
        } finally {
            json.writerWithDefaultPrettyPrinter()
                    .writeValue(directory.resolve("report.json").toFile(), report);
        }
    }

    private static boolean limitObserved(Map<?, ?> phase) {
        var http = (Map<?, ?>) phase.get("httpLatency");
        var freshness = (Map<?, ?>) phase.get("observedFreshness");
        return ((Number) phase.get("committedFactsWithinPlannedWindow")).doubleValue()
                        < ((Number) phase.get("plannedFacts")).doubleValue() * 0.99
                || ((Number) phase.get("failedQueries")).doubleValue()
                        > ((Number) phase.get("scheduledQueries")).doubleValue() * 0.001
                || ((Number) phase.get("notScheduledQueries")).longValue() != 0
                || ((Number) phase.get("rejectedQueries")).longValue() != 0
                || ((Number) http.get("p95Ms")).doubleValue() > 200
                || ((Number) http.get("p99Ms")).doubleValue() > 500
                || ((Number) phase.get("freshnessEligibleUnknown")).longValue() > 0
                || ((Number) phase.get("freshnessProvenOverFiveSeconds")).doubleValue()
                        > ((Number) phase.get("freshnessEligibleSamples")).doubleValue() * 0.01
                || (freshness.containsKey("p99Ms") && ((Number) freshness.get("p99Ms")).doubleValue() > 5000);
    }

    private record LoadProfile(String name, int rate, int queries, String distribution, int seconds) {}

    private static List<Object> topicStorage(String bootstrap) throws Exception {
        try (var admin =
                org.apache.kafka.clients.admin.Admin.create(java.util.Map.of("bootstrap.servers", bootstrap))) {
            var ids = admin.describeCluster().nodes().get(10, TimeUnit.SECONDS).stream()
                    .map(org.apache.kafka.common.Node::id)
                    .toList();
            var result = new java.util.ArrayList<Object>();
            for (var broker : admin.describeLogDirs(ids)
                    .allDescriptions()
                    .get(10, TimeUnit.SECONDS)
                    .entrySet())
                for (var log : broker.getValue().entrySet())
                    for (var replica : log.getValue().replicaInfos().entrySet())
                        result.add(java.util.Map.of(
                                "broker",
                                broker.getKey(),
                                "topic",
                                replica.getKey().topic(),
                                "partition",
                                replica.getKey().partition(),
                                "bytes",
                                replica.getValue().size()));
            return result;
        }
    }

    private static String sourceHash() throws Exception {
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        for (Path directory : List.of(Path.of("src/main"))) {
            try (var files = Files.walk(directory)) {
                for (Path path : files.filter(Files::isRegularFile).sorted().toList()) {
                    digest.update(path.toString().replace('\\', '/').getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    digest.update(Files.readAllBytes(path));
                }
            }
        }
        for (String name : List.of(
                "ProjectionComparisonTest",
                "ProjectionLoadDriver",
                "ProjectionProgressSampler",
                "ProjectionFixture",
                "ProjectionDirectPreview",
                "ProjectionBenchmarkMain",
                "ProjectionProcessMain",
                "ProjectionProcessLauncher",
                "ProjectionJvmSampler",
                "ProjectionKafkaIntegrationTest")) {
            Path path = Path.of(
                    "src/test/java/com/sparta/ticket/payment/infrastructure/persistence/settlement/" + name + ".java");
            digest.update(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update(Files.readAllBytes(path));
        }
        digest.update(Files.readAllBytes(Path.of("../sql/payment-service/schema.sql")));
        digest.update(Files.readAllBytes(Path.of("build.gradle")));
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private JsonNode response(int port, long seller) {
        try {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                            + "/api/seller/settlements/preview?from=2026-10-11&to=2026-11-10"))
                    .timeout(Duration.ofSeconds(3))
                    .header(UserHeaders.USER_ID, Long.toString(seller))
                    .header(UserHeaders.USERNAME, "measurement")
                    .header(UserHeaders.ROLE, "SELLER")
                    .GET()
                    .build();
            var result = http.send(request, HttpResponse.BodyHandlers.ofString());
            return result.statusCode() == 200 ? json.readTree(result.body()) : null;
        } catch (Exception exception) {
            return null;
        }
    }

    private static void await(java.util.function.BooleanSupplier done, Process process, Duration limit)
            throws InterruptedException {
        long deadline = System.nanoTime() + limit.toNanos();
        while (!done.getAsBoolean() && System.nanoTime() < deadline) {
            assertThat(process.isAlive()).as("시험 JVM 생존").isTrue();
            Thread.sleep(100);
        }
        assertThat(done.getAsBoolean()).as("제한 시간 내 시험 JVM 준비").isTrue();
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
