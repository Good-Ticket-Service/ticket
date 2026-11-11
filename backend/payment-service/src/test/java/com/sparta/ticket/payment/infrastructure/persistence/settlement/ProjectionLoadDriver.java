package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.common.auth.UserHeaders;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.SettlementFactJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.SettlementSourceJdbc;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/** 개방형 부하 발생기. 실제 PG 승인량과 구분하고 지연된 공급·미전송·미관측을 보존한다. */
final class ProjectionLoadDriver {
    private final JdbcTemplate jdbc;
    private final String mode;
    private final int port;
    private final Path output;
    private final PostgreSQLContainer<?> postgres;
    private final org.testcontainers.containers.GenericContainer<?> broker;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final long[][] expected = new long[1001][4];
    private final AtomicReference<ProjectionProgressSampler.Source> sourceProgress = new AtomicReference<>();
    private int nextSale;

    ProjectionLoadDriver(
            JdbcTemplate jdbc,
            String mode,
            int port,
            int history,
            Path output,
            PostgreSQLContainer<?> postgres,
            org.testcontainers.containers.GenericContainer<?> broker) {
        this.jdbc = jdbc;
        this.mode = mode;
        this.port = port;
        this.output = output;
        this.postgres = postgres;
        this.broker = broker;
        this.nextSale = history * 95 / 100;
        jdbc.query("""
                SELECT p.seller_id,count(*) FILTER(WHERE t.source_type='SALE'),
                  coalesce(sum(s.seller_amount) FILTER(WHERE t.source_type='SALE'),0)::bigint,
                  count(*) FILTER(WHERE t.source_type='REFUND'),
                  coalesce(sum(s.seller_amount) FILTER(WHERE t.source_type='REFUND'),0)::bigint
                FROM p_ledger_transactions t JOIN p_payments p ON p.id=t.payment_id
                JOIN p_settlement_items s ON s.payment_id=p.id AND s.kind='SALE'
                WHERE t.source_type='SALE' AND t.action='POST'
                  OR t.source_type='REFUND' AND t.action IN ('REVERSE','RECEIVABLE')
                GROUP BY p.seller_id
                """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            for (int i = 0; i < 4; i++) expected[rs.getInt(1)][i] = rs.getLong(i + 2);
        });
        refreshProgress();
        assertThat(sourceProgress.get().facts()).isEqualTo(history);
    }

    static int seller(int id) {
        int schedule = id % 19 == 0 ? 1 + (id / 19 - 1) % 500 : 501 + (id - id / 19 - 1) % 9500;
        return switch (System.getProperty("projection.distribution", "UNIFORM")) {
            case "SELLER_SKEW" -> (schedule - 1) % 100 < 80 ? 1 + (schedule - 1) % 10 : 11 + (schedule - 1) % 990;
            case "HOT_KEY" -> id % 2 == 0 ? 1 : 2 + (schedule - 1) % 999;
            default -> 1 + (schedule - 1) % 1000;
        };
    }

    Map<String, Object> run(Process api, Process engine) throws Exception {
        var result = new LinkedHashMap<String, Object>();
        Process memory = null;
        String monitor = System.getProperty("projection.monitor.script", "");
        try (var progress = new ProjectionProgressSampler(
                jdbc, mode, sourceProgress, output.resolve("projection-progress.jsonl"))) {
            if (!monitor.isBlank()) {
                var args = new ArrayList<>(List.of(
                        "python",
                        monitor,
                        output.resolve("process-memory.jsonl").toString(),
                        "api=" + api.pid(),
                        "generator=" + ProcessHandle.current().pid()));
                if (engine != null) args.add("engine=" + engine.pid());
                memory = new ProcessBuilder(args)
                        .redirectErrorStream(true)
                        .redirectOutput(output.resolve("memory-monitor.log").toFile())
                        .start();
            }
            result.put("distribution", System.getProperty("projection.distribution", "UNIFORM"));
            result.put("sourceBatchFacts", 100);
            result.put("generatorSeed", 334);
            result.put("sourceScope", "synthetic valid ledger commits, not checkout or PG approval throughput");
            result.put(
                    "freshnessScope",
                    "uniform planned batch and fact selection per logical source second; ten probe slots per second within query load; commit acknowledgement to HTTP observation upper bound; censored samples and supply shortfalls retained");
            result.put(
                    "bootstrapStatements",
                    jdbc.queryForList(
                            "SELECT query,calls,rows,total_exec_time,shared_blks_hit,shared_blks_read,wal_bytes FROM public.pg_stat_statements ORDER BY total_exec_time DESC"));
            result.put("warmup", phase("warmup", Integer.getInteger("projection.warmSeconds", 120)));
            jdbc.execute("SELECT public.pg_stat_statements_reset()");
            result.put("databaseBefore", database());
            result.put("containerBefore", container(postgres));
            result.put("brokerBefore", container(broker));
            String before = jdbc.queryForObject("SELECT pg_current_wal_lsn()::text", String.class);
            result.put("measurement", phase("measurement", Integer.getInteger("projection.measureSeconds", 300)));
            result.put(
                    "walBytes",
                    jdbc.queryForObject("SELECT pg_wal_lsn_diff(pg_current_wal_lsn(),?::pg_lsn)", Long.class, before));
            result.put("databaseAfter", database());
            result.put("containerAfter", container(postgres));
            result.put("brokerAfter", container(broker));
            result.put(
                    "statements",
                    jdbc.queryForList(
                            "SELECT query,calls,rows,total_exec_time,shared_blks_hit,shared_blks_read,temp_blks_read,temp_blks_written,wal_records,wal_fpi,wal_bytes FROM public.pg_stat_statements ORDER BY total_exec_time DESC"));
            if (!"A0".equals(mode)) {
                long started = System.nanoTime();
                long deadline = started + Duration.ofMinutes(5).toNanos();
                while (backlog() > 0 && System.nanoTime() < deadline) Thread.sleep(250);
                result.put("drainMs", (System.nanoTime() - started) / 1_000_000.0);
                result.put("remainingBacklog", backlog());
                assertThat(backlog()).as("입력 종료 후 전체 적용").isZero();
                result.put(
                        "projectionErrors",
                        jdbc.queryForObject(
                                "SELECT count(*) FROM p_settlement_projection_errors WHERE NOT resolved", Long.class));
                assertThat(result.get("projectionErrors")).isEqualTo(0L);
                long differences = keyDifferences(jdbc);
                long totalsDeadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
                while (differences != 0 && System.nanoTime() < totalsDeadline) {
                    Thread.sleep(500);
                    differences = keyDifferences(jdbc);
                }
                result.put("independentPerKeyDifferences", differences);
                assertThat(differences).as("원 결제/환불 고정 금액과 모든 키 비교").isZero();
            }
            result.put("totalFinalSales", nextSale);
            return result;
        } finally {
            if (memory != null) {
                memory.destroy();
                if (!memory.waitFor(5, TimeUnit.SECONDS)) {
                    memory.destroyForcibly();
                    assertThat(memory.waitFor(5, TimeUnit.SECONDS)).isTrue();
                }
            }
            json.writerWithDefaultPrettyPrinter()
                    .writeValue(output.resolve("load-report.json").toFile(), result);
        }
    }

    private Map<String, Object> phase(String name, int seconds) throws Exception {
        int sourceRate = Integer.getInteger("projection.sourceRate", 100);
        int queryRate = Integer.getInteger("projection.queryRate", 50);
        checkHostBudget();
        boolean burst =
                "measurement".equals(name) && "BURST".equals(System.getProperty("projection.scenario", "STEADY"));
        if (burst && seconds != 480) throw new IllegalArgumentException("버스트는 120초·60초·300초 순서다");
        if (sourceRate <= 0 || queryRate < 10 || seconds <= 0) throw new IllegalArgumentException("부하 설정 오류");
        var requests = new ConcurrentLinkedQueue<Map<String, Object>>();
        var samples = new ConcurrentLinkedQueue<Sample>();
        var pending = new ConcurrentLinkedQueue<Sample>();
        var sources = new ConcurrentLinkedQueue<Map<String, Object>>();
        var queryPool = new ThreadPoolExecutor(64, 64, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1000));
        var sourcePool = Executors.newSingleThreadExecutor();
        var failure = new AtomicReference<Throwable>();
        var rejected = new AtomicLong();
        var committedFacts = new AtomicLong();
        long start = System.nanoTime();
        long end = start + Duration.ofSeconds(seconds).toNanos();
        long epoch = System.currentTimeMillis();
        sourcePool.submit(() -> {
            long planned = start;
            long nextBudgetCheck = start;
            long samplingSlot = -1;
            int samplingBatch = 0, selectedBatch = 0;
            var samplingRandom = new Random(334);
            var source = new SettlementSourceJdbc(jdbc);
            var journal = new SettlementFactJdbc(jdbc, source);
            var tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
            try {
                while (planned < end && !Thread.currentThread().isInterrupted()) {
                    pauseUntil(planned);
                    if (System.nanoTime() >= end) break;
                    if (System.nanoTime() >= nextBudgetCheck) {
                        checkHostBudget();
                        nextBudgetCheck =
                                System.nanoTime() + Duration.ofSeconds(10).toNanos();
                    }
                    long began = System.nanoTime();
                    int targetRate = sourceRate
                            * (burst && planned - start >= 120_000_000_000L && planned - start < 180_000_000_000L
                                    ? 4
                                    : 1);
                    int from = nextSale;
                    tx.executeWithoutResult(status -> {
                        long floor = jdbc.queryForObject(
                                "SELECT coalesce(max(id),0) FROM p_ledger_transactions", Long.class);
                        ProjectionFixture.seed(
                                jdbc,
                                from,
                                100,
                                false,
                                ProjectionFixture.Distribution.valueOf(
                                        System.getProperty("projection.distribution", "UNIFORM")));
                        if (!"A0".equals(mode))
                            source.facts(" AND t.id>?", floor).forEach(journal::save);
                    });
                    long committed = System.nanoTime();
                    nextSale += 95;
                    addExpected(from, nextSale);
                    refreshProgress();
                    long slot = (planned - start) / 1_000_000_000L;
                    if (slot != samplingSlot) {
                        samplingSlot = slot;
                        samplingBatch = 0;
                        int batches = Math.max(1, (int)
                                (Math.ceil((slot + 1) * targetRate / 100.0) - Math.ceil(slot * targetRate / 100.0)));
                        selectedBatch = samplingRandom.nextInt(batches);
                    }
                    if (samplingBatch++ == selectedBatch) {
                        int selected = samplingRandom.nextInt(100);
                        int payment = selected < 95 ? from + selected + 1 : from + 19 * (selected - 94);
                        int owner = seller(payment);
                        var sample = new Sample(
                                owner, expected[owner].clone(), committed, payment, selected < 95 ? "SALE" : "REFUND");
                        samples.add(sample);
                        pending.add(sample);
                    }
                    committedFacts.addAndGet(100);
                    sources.add(Map.of(
                            "scheduledOffsetMs",
                            (planned - start) / 1_000_000.0,
                            "commitOffsetMs",
                            (committed - start) / 1_000_000.0,
                            "supplyDelayMs",
                            (began - planned) / 1_000_000.0,
                            "transactionMs",
                            (committed - began) / 1_000_000.0,
                            "facts",
                            100,
                            "logicalSecond",
                            slot,
                            "targetFactsPerSecond",
                            targetRate));
                    planned += 100_000_000_000L / targetRate;
                }
            } catch (Throwable exception) {
                failure.set(exception);
            }
        });
        int scheduled = 0;
        var random = new Random(334);
        long queryStep = 1_000_000_000L / queryRate;
        long nextProbe = start;
        try {
            for (long planned = start; planned < end; planned += queryStep) {
                pauseUntil(planned);
                if (failure.get() != null) break;
                if (System.nanoTime() >= end) break;
                int owner = 1 + random.nextInt(1000);
                boolean probe = planned >= nextProbe;
                if (probe) nextProbe += 100_000_000L;
                long target = planned;
                scheduled++;
                try {
                    queryPool.execute(() -> query(owner, probe ? pending.poll() : null, pending, requests, target));
                } catch (RejectedExecutionException exception) {
                    rejected.incrementAndGet();
                }
            }
        } finally {
            sourcePool.shutdown();
            queryPool.shutdown();
            if (!sourcePool.awaitTermination(30, TimeUnit.SECONDS)) {
                sourcePool.shutdownNow();
                boolean stopped = sourcePool.awaitTermination(10, TimeUnit.SECONDS);
                failure.compareAndSet(null, new AssertionError("원천 발생기 종료 시간 초과; stopped=" + stopped));
            }
            if (!queryPool.awaitTermination(30, TimeUnit.SECONDS)) {
                int abandoned = queryPool.shutdownNow().size();
                rejected.addAndGet(abandoned);
                boolean stopped = queryPool.awaitTermination(10, TimeUnit.SECONDS);
                failure.compareAndSet(
                        null, new AssertionError("조회 발생기 종료 시간 초과; abandoned=" + abandoned + "; stopped=" + stopped));
            }
            Files.writeString(output.resolve(name + "-requests.json"), json.writeValueAsString(requests));
            Files.writeString(output.resolve(name + "-sources.json"), json.writeValueAsString(sources));
            var observations = new ArrayList<Map<String, Object>>();
            for (Sample sample : samples)
                observations.add(Map.of(
                        "seller",
                        sample.seller,
                        "expected",
                        sample.expected,
                        "paymentId",
                        sample.payment,
                        "factKind",
                        sample.kind,
                        "probeAttempts",
                        sample.attempts,
                        "lastNegativeProbeLowerBoundMs",
                        sample.lastNegativeProbe == 0
                                ? -1
                                : (sample.lastNegativeProbe - sample.committed) / 1_000_000.0,
                        "commitOffsetMs",
                        (sample.committed - start) / 1_000_000.0,
                        "observed",
                        sample.observed != 0,
                        "freshnessMs",
                        sample.observed == 0 ? -1 : (sample.observed - sample.committed) / 1_000_000.0));
            Files.writeString(output.resolve(name + "-freshness.json"), json.writeValueAsString(observations));
        }
        if (failure.get() != null) throw new AssertionError("원천 발생기 실패", failure.get());
        long freshnessBudget = Duration.ofSeconds(5).toNanos();
        long eligible = samples.stream()
                .filter(s -> end - s.committed >= freshnessBudget)
                .count();
        long withinBudget = samples.stream()
                .filter(s -> end - s.committed >= freshnessBudget
                        && s.observed != 0
                        && s.observed - s.committed <= freshnessBudget)
                .count();
        long provenLate = samples.stream()
                .filter(s ->
                        end - s.committed >= freshnessBudget && s.lastNegativeProbe - s.committed >= freshnessBudget)
                .count();
        var summary = new LinkedHashMap<String, Object>();
        summary.put("startEpochMs", epoch);
        summary.put("endEpochMs", System.currentTimeMillis());
        summary.put("elapsedIncludingFinalRequestsSeconds", (System.nanoTime() - start) / 1_000_000_000.0);
        summary.put("plannedSeconds", seconds);
        summary.put("freshnessEligibleSamples", eligible);
        summary.put("freshnessEligibleWithinFiveSeconds", withinBudget);
        summary.put("freshnessProvenOverFiveSeconds", provenLate);
        summary.put("freshnessEligibleUnknown", eligible - withinBudget - provenLate);
        summary.put(
                "freshnessBudgetScope",
                "sampled facts with five seconds before planned end; observation is an upper bound, negative probe a lower bound; unknown triggers confirmation, not a proven SLO failure");
        summary.put("targetFactsPerSecond", sourceRate);
        summary.put("scenario", burst ? "BURST" : "STEADY");
        summary.put("plannedFacts", sourceRate * (burst ? 660L : seconds));
        summary.put("notSuppliedFacts", Math.max(0, sourceRate * (burst ? 660L : seconds) - committedFacts.get()));
        summary.put("committedFacts", committedFacts.get());
        summary.put(
                "committedFactsWithinPlannedWindow",
                sources.stream()
                                .filter(s -> (double) s.get("commitOffsetMs") < seconds * 1000.0)
                                .count()
                        * 100);
        summary.put("committedFactsPerPlannedSecond", committedFacts.get() / (double) seconds);
        summary.put("targetQueriesPerSecond", queryRate);
        summary.put("scheduledQueries", scheduled);
        summary.put("notScheduledQueries", Math.max(0, seconds * queryRate - scheduled));
        summary.put("rejectedQueries", rejected.get());
        summary.put("completedQueries", requests.size());
        summary.put(
                "sentQueries",
                requests.stream()
                        .filter(r -> Boolean.TRUE.equals(r.get("httpSent")))
                        .count());
        summary.put(
                "notSentQueueDeadlineQueries",
                requests.stream()
                        .filter(r -> "QUEUE_DEADLINE_EXCEEDED".equals(r.get("error")))
                        .count());
        summary.put(
                "successfulQueries",
                requests.stream()
                        .filter(r -> Integer.valueOf(200).equals(r.get("status")))
                        .count());
        summary.put(
                "failedQueries",
                requests.stream()
                        .filter(r -> !Integer.valueOf(200).equals(r.get("status")))
                        .count());
        summary.put(
                "staleResponses",
                requests.stream()
                        .filter(r -> Boolean.TRUE.equals(r.get("stale")))
                        .count());
        summary.put(
                "httpLatency",
                percentiles(requests.stream()
                        .mapToDouble(r -> (double) r.get("latencyMs"))
                        .toArray()));
        summary.put(
                "successfulHttpLatency",
                percentiles(requests.stream()
                        .filter(r -> Integer.valueOf(200).equals(r.get("status")))
                        .mapToDouble(r -> (double) r.get("latencyMs"))
                        .toArray()));
        summary.put(
                "generatorQueueDelay",
                percentiles(requests.stream()
                        .mapToDouble(r -> (double) r.get("queueDelayMs"))
                        .toArray()));
        summary.put("sampleCount", samples.size());
        summary.put(
                "censoredFreshnessSamples",
                samples.stream().filter(s -> s.observed == 0).count());
        summary.put(
                "observedFreshness",
                percentiles(samples.stream()
                        .filter(s -> s.observed != 0)
                        .mapToDouble(s -> (s.observed - s.committed) / 1_000_000.0)
                        .toArray()));
        return summary;
    }

    private void query(
            int randomOwner,
            Sample sample,
            ConcurrentLinkedQueue<Sample> pending,
            ConcurrentLinkedQueue<Map<String, Object>> requests,
            long planned) {
        long start = System.nanoTime();
        int status = -1;
        boolean stale = false;
        boolean sent = false;
        String error = "";
        try {
            if (start - planned > Duration.ofSeconds(5).toNanos()) {
                status = -2;
                error = "QUEUE_DEADLINE_EXCEEDED";
                return;
            }
            int owner = sample == null ? randomOwner : sample.seller;
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                            + "/api/seller/settlements/preview?from=2026-10-11&to=2026-11-10"))
                    .header(UserHeaders.USER_ID, Integer.toString(owner))
                    .header(UserHeaders.USERNAME, "measurement")
                    .header(UserHeaders.ROLE, "SELLER")
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            sent = true;
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            status = response.statusCode();
            if (status == 200) {
                JsonNode data = json.readTree(response.body()).path("data");
                stale = "STALE".equals(data.path("status").asText());
                if (sample != null) {
                    sample.attempts++;
                    JsonNode totals = data.path("totals");
                    String[] fields = {"saleCount", "saleSellerAmount", "refundCount", "refundSellerAmount"};
                    boolean complete = !totals.isMissingNode() && !totals.isNull();
                    for (int i = 0; i < fields.length; i++)
                        complete &= totals.path(fields[i]).asLong(-1) >= sample.expected[i];
                    if (complete) sample.observed = System.nanoTime();
                    else sample.lastNegativeProbe = start;
                }
            }
        } catch (Exception exception) {
            error = exception.getClass().getSimpleName();
        } finally {
            if (sample != null && sample.observed == 0) pending.add(sample);
            requests.add(Map.of(
                    "status",
                    status,
                    "stale",
                    stale,
                    "error",
                    error,
                    "httpSent",
                    sent,
                    "latencyMs",
                    (System.nanoTime() - start) / 1_000_000.0,
                    "queueDelayMs",
                    (start - planned) / 1_000_000.0));
        }
    }

    private void addExpected(int from, int to) {
        for (int id = from + 1; id <= to; id++) {
            long[] totals = expected[seller(id)];
            long amount = id % 2 == 0 ? 8680 : 8900;
            totals[0]++;
            totals[1] += amount;
            if (id % 19 == 0) {
                totals[2]++;
                totals[3] += amount;
            }
        }
    }

    private void refreshProgress() {
        long facts = 0, gross = 0, net = 0;
        for (long[] owner : expected) {
            facts += owner[0] + owner[2];
            gross += owner[1] + owner[3];
            net += owner[1] - owner[3];
        }
        sourceProgress.set(new ProjectionProgressSampler.Source(facts, gross, net));
    }

    private void checkHostBudget() throws Exception {
        var os = (com.sun.management.OperatingSystemMXBean)
                java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        long disk = Files.getFileStore(output).getUsableSpace();
        long available = os.getFreeMemorySize();
        long total = os.getTotalMemorySize();
        assertThat(disk).as("시험 디스크 여유20GiB").isGreaterThanOrEqualTo(20L * 1024 * 1024 * 1024);
        assertThat(available).as("호스트 가용 물리 메모리10%%").isGreaterThanOrEqualTo(total / 10);
    }

    private long backlog() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM p_settlement_projection_deliveries d JOIN p_settlement_projection_head h ON h.build_id=d.build_id LEFT JOIN p_settlement_projection_applied a ON a.build_id=d.build_id AND a.fact_key=d.fact_key WHERE a.fact_key IS NULL",
                Long.class);
    }

    static long keyDifferences(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                WITH original AS (
                  SELECT p.seller_id,p.schedule_id,t.occurred_at::date AS business_date,
                    count(*) FILTER(WHERE t.source_type='SALE') AS sale_count,
                    coalesce(sum(s.seller_amount) FILTER(WHERE t.source_type='SALE'),0)::bigint AS sale_amount,
                    count(*) FILTER(WHERE t.source_type='REFUND') AS refund_count,
                    coalesce(sum(s.seller_amount) FILTER(WHERE t.source_type='REFUND'),0)::bigint AS refund_amount
                  FROM p_ledger_transactions t JOIN p_payments p ON p.id=t.payment_id
                  JOIN p_settlement_items s ON s.payment_id=p.id AND s.kind='SALE'
                  WHERE t.source_type='SALE' AND t.action='POST' OR t.source_type='REFUND' AND t.action IN ('REVERSE','RECEIVABLE')
                  GROUP BY p.seller_id,p.schedule_id,t.occurred_at::date
                ), projected AS (
                  SELECT t.* FROM p_settlement_projection_totals t JOIN p_settlement_projection_head h ON h.build_id=t.build_id
                ) SELECT count(*) FROM original o FULL JOIN projected p USING(seller_id,schedule_id,business_date)
                  WHERE o.sale_count IS DISTINCT FROM p.sale_count OR o.sale_amount IS DISTINCT FROM p.sale_amount
                    OR o.refund_count IS DISTINCT FROM p.refund_count OR o.refund_amount IS DISTINCT FROM p.refund_amount
                """, Long.class);
    }

    private Map<String, Object> database() {
        return jdbc.queryForMap(
                "SELECT blks_read,blks_hit,temp_bytes,deadlocks,blk_read_time,blk_write_time FROM pg_stat_database WHERE datname=current_database()");
    }

    private Map<String, String> container(org.testcontainers.containers.GenericContainer<?> target) throws Exception {
        return Map.of(
                "cpu",
                target.execInContainer("cat", "/sys/fs/cgroup/cpu.stat").getStdout(),
                "memoryCurrent",
                target.execInContainer("cat", "/sys/fs/cgroup/memory.current").getStdout(),
                "io",
                target.execInContainer("cat", "/sys/fs/cgroup/io.stat").getStdout());
    }

    private static Map<String, Object> percentiles(double[] values) {
        if (values.length == 0) return Map.of("count", 0);
        Arrays.sort(values);
        return Map.of(
                "count",
                values.length,
                "p50Ms",
                values[(int) Math.ceil(values.length * .5) - 1],
                "p95Ms",
                values[(int) Math.ceil(values.length * .95) - 1],
                "p99Ms",
                values[(int) Math.ceil(values.length * .99) - 1],
                "meanMs",
                Arrays.stream(values).average().orElseThrow(),
                "maxMs",
                values[values.length - 1]);
    }

    private static void pauseUntil(long planned) {
        while (System.nanoTime() < planned) LockSupport.parkNanos(Math.min(1_000_000, planned - System.nanoTime()));
    }

    private static final class Sample {
        private final int seller;
        private final long[] expected;
        private final long committed;
        private final int payment;
        private final String kind;
        private volatile long observed;
        private volatile long lastNegativeProbe;
        private volatile int attempts;

        private Sample(int seller, long[] expected, long committed, int payment, String kind) {
            this.seller = seller;
            this.expected = expected;
            this.committed = committed;
            this.payment = payment;
            this.kind = kind;
        }
    }
}
