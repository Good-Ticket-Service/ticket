package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.payment.application.port.settlement.PayoutAgency;
import com.sparta.ticket.payment.application.service.settlement.SettlementReconciliationJob;
import com.sparta.ticket.payment.domain.repository.settlement.SettlementReconcileRepository.CheckResult;
import com.sparta.ticket.payment.infrastructure.settlement.ReconcileBatchConfig;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.support.JobRepositoryFactoryBean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/** 실제 운영 SQL의 DB 비용 기준선. Spring 컨텍스트·외부 HTTP 시간은 포함하지 않는다. */
@EnabledIfSystemProperty(named = "reconcile.measure", matches = "true")
class ReconcilePerformanceTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final int rows = Integer.getInteger("reconcile.rows", 10000);
    private final String phase = System.getProperty("reconcile.phase", "M0");
    private final Path output = Path.of(System.getProperty("reconcile.output", "build/reconcile-performance"))
            .toAbsolutePath();

    @Test
    @EnabledIfSystemProperty(named = "reconcile.phase", matches = "M0|M1|M2")
    void measureExistingChecks() throws Exception {
        Files.createDirectories(output);
        try (var postgres =
                new PostgreSQLContainer<>("postgres:16").withCommand("postgres", "-c", "track_io_timing=on")) {
            postgres.start();
            var dataSource =
                    new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            new ResourceDatabasePopulator(new FileSystemResource("../sql/payment-service/schema.sql"))
                    .execute(dataSource);
            var jdbc = new JdbcTemplate(dataSource);
            long seedStart = System.nanoTime();
            new TransactionTemplate(new DataSourceTransactionManager(dataSource))
                    .executeWithoutResult(status -> seed(jdbc, phase.equals("M2") ? rows - 10000 : rows));
            long seedMs = elapsed(seedStart);
            jdbc.execute("ANALYZE");
            var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            transaction.setReadOnly(true);
            var adapter = new SettlementReconcileJdbcAdapter(jdbc, Duration.ofSeconds(120));
            if (phase.equals("M2")) {
                measureIncremental(jdbc, adapter, new DataSourceTransactionManager(dataSource), postgres);
                return;
            }
            Supplier<List<CheckResult>> run = phase.equals("M0")
                    ? () -> transaction.execute(status -> adapter.runChecks(null))
                    : batchRun(jdbc, adapter, new DataSourceTransactionManager(dataSource), false, "FULL");
            var checks = checks();
            List<Object> samples = new ArrayList<>();
            for (int iteration = 0; iteration < 4; iteration++) {
                long started = System.nanoTime();
                long cpu = ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
                var results = run.get();
                Map<String, Object> sample = new LinkedHashMap<>();
                sample.put("iteration", iteration);
                sample.put("warmup", iteration == 0);
                sample.put("wallMs", elapsed(started));
                sample.put(
                        "clientThreadCpuNs", ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime() - cpu);
                sample.put("checks", results);
                sample.put("databaseStats", jdbc.queryForMap("""
                        SELECT blks_read,blks_hit,temp_files,temp_bytes,blk_read_time,blk_write_time,
                          deadlocks FROM pg_stat_database WHERE datname=current_database()
                        """));
                samples.add(sample);
                json.writerWithDefaultPrettyPrinter()
                        .writeValue(
                                output.resolve(phase + "-" + rows + "-samples.json")
                                        .toFile(),
                                samples);
                assertThat(results).hasSize(22).allSatisfy(result -> {
                    assertThat(result.completed()).as(result.check()).isTrue();
                    assertThat(result.violations()).as(result.check()).isZero();
                });
            }
            Map<String, Object> plans = new LinkedHashMap<>();
            transaction.executeWithoutResult(status -> checks.forEach((name, query) -> {
                String sql = "SELECT count(*),array_to_string((array_agg(v.id ORDER BY v.id))[1:10],',') FROM ("
                        + query.replace(":since", "NULL") + ") v(id)";
                String plan =
                        jdbc.queryForObject("EXPLAIN (ANALYZE,BUFFERS,WAL,SETTINGS,FORMAT JSON) " + sql, String.class);
                try {
                    plans.put(name, json.readTree(plan));
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
            }));
            Map<String, Object> environment = new LinkedHashMap<>();
            environment.put("phase", phase + "_NO_HTTP_TARGETS");
            environment.put("rows", rows);
            environment.put("entries", jdbc.queryForObject("SELECT count(*) FROM p_ledger_entries", Long.class));
            environment.put("seedMs", seedMs);
            environment.put("java", System.getProperty("java.version"));
            environment.put("processors", Runtime.getRuntime().availableProcessors());
            environment.put("postgres", jdbc.queryForObject("SELECT version()", String.class));
            environment.put("settings", jdbc.queryForList("""
                    SELECT name,setting,unit FROM pg_settings WHERE name IN
                    ('shared_buffers','work_mem','max_parallel_workers_per_gather','track_io_timing','max_connections')
                    ORDER BY name
                    """));
            environment.put("financial", jdbc.queryForMap("""
                    SELECT sum(debit) AS debit,sum(credit) AS credit,
                      sum(credit-debit) FILTER(WHERE account='SELLER_PAYABLE') AS payable FROM p_ledger_entries
                    """));
            json.writerWithDefaultPrettyPrinter()
                    .writeValue(
                            output.resolve(phase + "-" + rows + "-environment.json")
                                    .toFile(),
                            environment);
            json.writerWithDefaultPrettyPrinter()
                    .writeValue(
                            output.resolve(phase + "-" + rows + "-plans.json").toFile(), plans);
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "reconcile.phase", matches = "WRITE")
    void measureWriterOverhead() throws Exception {
        Files.createDirectories(output);
        try (var postgres = new PostgreSQLContainer<>("postgres:16")) {
            postgres.start();
            String schema = Files.readString(Path.of("../sql/payment-service/schema.sql"));
            var control = new JdbcTemplate(
                    new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
            Map<String, com.zaxxer.hikari.HikariDataSource> pools = new LinkedHashMap<>();
            try {
                for (String variant : List.of("baseline", "guarded")) {
                    control.execute("CREATE SCHEMA " + variant);
                    var pool = new com.zaxxer.hikari.HikariDataSource();
                    pool.setJdbcUrl(postgres.getJdbcUrl()
                            + (postgres.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + variant);
                    pool.setUsername(postgres.getUsername());
                    pool.setPassword(postgres.getPassword());
                    pool.setMaximumPoolSize(1);
                    pools.put(variant, pool);
                    String ddl =
                            variant.equals("baseline") ? schema.substring(0, schema.indexOf("-- 신규 원장 메타데이터")) : schema;
                    new ResourceDatabasePopulator(new org.springframework.core.io.ByteArrayResource(
                                    ddl.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                            .execute(pool);
                    var jdbc = new JdbcTemplate(pool);
                    new TransactionTemplate(new DataSourceTransactionManager(pool))
                            .executeWithoutResult(status -> seed(jdbc, rows));
                    jdbc.execute("TRUNCATE p_ledger_transactions CASCADE");
                }
                var journals = java.util.stream.LongStream.rangeClosed(1, rows)
                        .mapToObj(com.sparta.ticket.payment.domain.model.settlement.LedgerMeasurementFixture::sale)
                        .toList();
                List<Map<String, Object>> samples = new ArrayList<>();
                for (int iteration = 0; iteration < 4; iteration++) {
                    for (String variant :
                            iteration % 2 == 0 ? List.of("baseline", "guarded") : List.of("guarded", "baseline")) {
                        var pool = pools.get(variant);
                        var jdbc = new JdbcTemplate(pool);
                        jdbc.execute("TRUNCATE p_ledger_transactions CASCADE");
                        var writer = new JdbcLedgerJournalWriter(
                                jdbc,
                                org.mockito.Mockito.mock(jakarta.persistence.EntityManager.class),
                                () -> java.util.Optional.of("measure"));
                        var tx = new TransactionTemplate(new DataSourceTransactionManager(pool));
                        var before = databaseCounters(jdbc);
                        var cpuBefore = containerCounters(postgres);
                        List<Long> latencies = new ArrayList<>();
                        long started = System.nanoTime();
                        for (var journal : journals) {
                            long rowStarted = System.nanoTime();
                            tx.executeWithoutResult(status -> writer.append(journal));
                            latencies.add(System.nanoTime() - rowStarted);
                        }
                        long wall = elapsed(started);
                        latencies.sort(Long::compare);
                        Map<String, Object> sample = new LinkedHashMap<>();
                        sample.put("variant", variant);
                        sample.put("warmup", iteration == 0);
                        sample.put("iteration", iteration);
                        sample.put("journals", rows);
                        sample.put("wallMs", wall);
                        sample.put("journalsPerSecond", rows * 1000.0 / wall);
                        sample.put("p50Ms", latencies.get(rows / 2) / 1000000.0);
                        sample.put("p95Ms", latencies.get((int) (rows * 0.95)) / 1000000.0);
                        sample.put("p99Ms", latencies.get((int) (rows * 0.99)) / 1000000.0);
                        sample.put("containerBefore", cpuBefore);
                        sample.put("containerAfter", containerCounters(postgres));
                        sample.put("databaseBefore", before);
                        sample.put("databaseAfter", databaseCounters(jdbc));
                        assertThat(jdbc.queryForObject("SELECT count(*) FROM p_ledger_transactions", Long.class))
                                .isEqualTo(rows);
                        assertThat(jdbc.queryForObject(
                                        "SELECT sum(debit)-sum(credit) FROM p_ledger_entries", Long.class))
                                .isZero();
                        samples.add(sample);
                        json.writerWithDefaultPrettyPrinter()
                                .writeValue(
                                        output.resolve("writer-" + rows + "-samples.json")
                                                .toFile(),
                                        samples);
                    }
                }
            } finally {
                pools.values().forEach(com.zaxxer.hikari.HikariDataSource::close);
            }
        }
    }

    private Supplier<List<CheckResult>> batchRun(
            JdbcTemplate jdbc,
            SettlementReconcileJdbcAdapter adapter,
            DataSourceTransactionManager manager,
            boolean incremental,
            String mode)
            throws Exception {
        var factory = new JobRepositoryFactoryBean();
        factory.setDataSource(jdbc.getDataSource());
        factory.setTransactionManager(manager);
        factory.afterPropertiesSet();
        var repository = factory.getObject();
        var launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(repository);
        launcher.afterPropertiesSet();
        var store = transactional(new ReconcileBatchJdbcStore(jdbc, adapter, "", incremental), manager);
        var reconciliation = new SettlementReconciliationJob(
                transactional(adapter, manager),
                org.mockito.Mockito.mock(PayoutAgency.class),
                Clock.systemUTC(),
                "",
                48,
                500);
        var job = new ReconcileBatchConfig().reconcileJob(repository, manager, store, reconciliation);
        return () -> {
            try {
                // Note: 서로 다른 수동 입력의 반복 측정이다. 중복 방지는 별도 시험에서 같은 키로 검증한다.
                var execution = launcher.run(
                        job,
                        new JobParametersBuilder()
                                .addString("businessDate", "2026-11-09")
                                .addString("mode", mode)
                                .addLong("runKey", System.nanoTime())
                                .toJobParameters());
                assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
                return store.results(
                        store.acceptedRun(execution.getJobInstance().getInstanceId()));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
    }

    private void measureIncremental(
            JdbcTemplate jdbc,
            SettlementReconcileJdbcAdapter adapter,
            DataSourceTransactionManager manager,
            PostgreSQLContainer<?> postgres)
            throws Exception {
        var tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        Supplier<List<CheckResult>> m0 = () -> tx.execute(status -> adapter.runChecks(null));
        var m1 = batchRun(jdbc, adapter, manager, false, "FULL");
        var m2 = batchRun(jdbc, adapter, manager, true, "DAILY");
        long baselineStarted = System.nanoTime();
        m2.get();
        long baselineMs = elapsed(baselineStarted);
        jdbc.execute("CREATE TABLE measure_initial_head AS SELECT * FROM p_reconcile_checkpoint");
        jdbc.execute("CREATE TABLE measure_initial_cache AS SELECT * FROM p_reconcile_account_balances");
        new TransactionTemplate(manager).executeWithoutResult(status -> seedRange(jdbc, rows - 10000, 10000));
        jdbc.execute("ANALYZE");
        Map<String, Supplier<List<CheckResult>>> variants = Map.of("M0", m0, "M1", m1, "M2", m2);
        List<Map<String, Object>> samples = new ArrayList<>();
        for (int iteration = 0; iteration <= Integer.getInteger("reconcile.repeats", 3); iteration++) {
            List<String> order = iteration == 0
                    ? List.of("M0", "M1", "M2")
                    : iteration % 3 == 1
                            ? List.of("M1", "M2", "M0")
                            : iteration % 3 == 2 ? List.of("M2", "M0", "M1") : List.of("M0", "M1", "M2");
            for (String variant : order) {
                if (variant.equals("M2")) restoreBaseline(jdbc);
                var beforeContainer = containerCounters(postgres);
                var beforeDatabase = databaseCounters(jdbc);
                long gcBefore = ManagementFactory.getGarbageCollectorMXBeans().stream()
                        .mapToLong(g -> Math.max(0, g.getCollectionTime()))
                        .sum();
                long cpu = ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
                long started = System.nanoTime();
                var results = variants.get(variant).get();
                long wall = elapsed(started);
                Map<String, Object> sample = new LinkedHashMap<>();
                sample.put("variant", variant);
                sample.put("iteration", iteration);
                sample.put("warmup", iteration == 0);
                sample.put("wallMs", wall);
                sample.put("containerBefore", beforeContainer);
                sample.put("containerAfter", containerCounters(postgres));
                sample.put("databaseBefore", beforeDatabase);
                sample.put("databaseAfter", databaseCounters(jdbc));
                sample.put(
                        "clientGcMs",
                        ManagementFactory.getGarbageCollectorMXBeans().stream()
                                        .mapToLong(g -> Math.max(0, g.getCollectionTime()))
                                        .sum()
                                - gcBefore);
                sample.put(
                        "clientHeapUsedBytes",
                        ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
                sample.put(
                        "clientThreadCpuNs", ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime() - cpu);
                sample.put("checks", results);
                if (!variant.equals("M0"))
                    sample.put(
                            "run",
                            jdbc.queryForMap("SELECT * FROM p_settlement_reconcile_runs ORDER BY id DESC LIMIT 1"));
                samples.add(sample);
                json.writerWithDefaultPrettyPrinter()
                        .writeValue(
                                output.resolve("paired-" + rows + "-samples.json")
                                        .toFile(),
                                samples);
                assertThat(results).hasSize(22).allSatisfy(check -> {
                    assertThat(check.completed()).as(check.check()).isTrue();
                    assertThat(check.violations()).as(check.check()).isZero();
                });
                if (variant.equals("M2"))
                    assertThat(jdbc.queryForObject(
                                    "SELECT delta_headers FROM p_settlement_reconcile_runs ORDER BY id DESC LIMIT 1",
                                    Long.class))
                            .isEqualTo(10000);
            }
        }
        restoreBaseline(jdbc);
        var plans = tx.execute(status -> {
            Map<String, Object> result = new LinkedHashMap<>();
            try {
                checks().forEach((name, sql) -> {
                    try {
                        result.put(
                                "FULL-" + name,
                                json.readTree(jdbc.queryForObject(
                                        "EXPLAIN (ANALYZE,BUFFERS,WAL,FORMAT JSON) SELECT count(*),array_to_string((array_agg(v.id ORDER BY v.id))[1:10],',') FROM ("
                                                + sql.replace(":since", "NULL") + ") v(id)",
                                        String.class)));
                    } catch (java.io.IOException e) {
                        throw new IllegalStateException(e);
                    }
                });
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            var incremental = new ReconcileIncrementalJdbc(jdbc);
            var state = incremental.prepare("DAILY");
            incremental.replacements(state).forEach((name, sql) -> {
                try {
                    result.put(
                            name,
                            json.readTree(jdbc.queryForObject(
                                    "EXPLAIN (ANALYZE,BUFFERS,WAL,FORMAT JSON) SELECT count(*),array_to_string((array_agg(v.id ORDER BY v.id))[1:10],',') FROM ("
                                            + sql + ") v(id)",
                                    String.class)));
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
            });
            result.put("deltaHeaderSelection", jdbc.queryForObject("""
                    EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) SELECT id FROM p_ledger_transactions
                    WHERE writer_xid>=pg_snapshot_xmin((SELECT snapshot::pg_snapshot FROM p_reconcile_checkpoint WHERE id=1))
                    AND NOT pg_visible_in_snapshot(writer_xid,(SELECT snapshot::pg_snapshot FROM p_reconcile_checkpoint WHERE id=1))
                    """, String.class));
            result.put("state", state);
            result.put("deltaEntrySelection", jdbc.queryForObject("""
                    EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON)
                    SELECT e.* FROM reconcile_delta_headers d CROSS JOIN LATERAL (
                      SELECT id,transaction_id,account,debit,credit FROM p_ledger_entries WHERE transaction_id=d.id OFFSET 0
                    ) e
                    """, String.class));
            return result;
        });
        json.writerWithDefaultPrettyPrinter()
                .writeValue(
                        output.resolve("paired-" + rows + "-incremental-plans.json")
                                .toFile(),
                        plans);
        Map<String, Object> environment = new LinkedHashMap<>();
        environment.put("rows", rows);
        environment.put("historicalRows", rows - 10000);
        environment.put("newRows", 10000);
        environment.put("baselineFullMs", baselineMs);
        environment.put("postgres", jdbc.queryForObject("SELECT version()", String.class));
        environment.put("java", System.getProperty("java.version"));
        environment.put("processors", Runtime.getRuntime().availableProcessors());
        Map<String, String> fingerprints = new LinkedHashMap<>();
        for (String file : List.of(
                "SettlementReconcileJdbcAdapter.java",
                "ReconcileBatchJdbcStore.java",
                "ReconcileIncrementalJdbc.java")) {
            var bytes = Files.readAllBytes(
                    Path.of("src/main/java/com/sparta/ticket/payment/infrastructure/persistence/settlement/" + file));
            fingerprints.put(
                    file,
                    java.util.HexFormat.of()
                            .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(bytes)));
        }
        environment.put("sourceSha256", fingerprints);
        environment.put(
                "settings",
                jdbc.queryForList(
                        "SELECT name,setting,unit FROM pg_settings WHERE name IN ('shared_buffers','work_mem','max_parallel_workers_per_gather','track_io_timing') ORDER BY name"));
        environment.put(
                "financial",
                jdbc.queryForMap(
                        "SELECT sum(debit) AS debit,sum(credit) AS credit,sum(credit-debit) FILTER(WHERE account='SELLER_PAYABLE') AS payable FROM p_ledger_entries"));
        json.writerWithDefaultPrettyPrinter()
                .writeValue(
                        output.resolve("paired-" + rows + "-environment.json").toFile(), environment);
    }

    private static void restoreBaseline(JdbcTemplate jdbc) {
        new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())).executeWithoutResult(status -> {
            jdbc.update("DELETE FROM p_reconcile_account_balances");
            jdbc.update("INSERT INTO p_reconcile_account_balances SELECT * FROM measure_initial_cache");
            jdbc.update("""
                    UPDATE p_reconcile_checkpoint c SET generation=b.generation,valid=b.valid,snapshot=b.snapshot,
                      run_id=b.run_id,accepted_at=b.accepted_at FROM measure_initial_head b WHERE c.id=b.id
                    """);
        });
    }

    private static Map<String, Object> databaseCounters(JdbcTemplate jdbc) {
        var counters = new LinkedHashMap<String, Object>(jdbc.queryForMap("""
                SELECT blks_read,blks_hit,temp_files,temp_bytes,blk_read_time,blk_write_time,deadlocks,
                  tup_returned,tup_fetched FROM pg_stat_database WHERE datname=current_database()
                """));
        counters.put(
                "walBytes",
                jdbc.queryForObject("SELECT pg_wal_lsn_diff(pg_current_wal_lsn(),'0/0')", java.math.BigDecimal.class));
        jdbc.queryForMap(
                        "SELECT checkpoints_timed,checkpoints_req,checkpoint_write_time,checkpoint_sync_time,buffers_checkpoint,buffers_backend,buffers_backend_fsync FROM pg_stat_bgwriter")
                .forEach((key, value) -> counters.put("background_" + key, value));
        return counters;
    }

    private static Map<String, Long> containerCounters(PostgreSQLContainer<?> postgres) throws Exception {
        var cpu = postgres.execInContainer("cat", "/sys/fs/cgroup/cpu.stat");
        var memory = postgres.execInContainer("cat", "/sys/fs/cgroup/memory.current");
        var peak = postgres.execInContainer("cat", "/sys/fs/cgroup/memory.peak");
        if (cpu.getExitCode() != 0 || memory.getExitCode() != 0 || peak.getExitCode() != 0)
            throw new IllegalStateException("컨테이너 cgroup v2 계측을 사용할 수 없다");
        long usage = cpu.getStdout()
                .lines()
                .filter(line -> line.startsWith("usage_usec "))
                .mapToLong(line -> Long.parseLong(line.substring(11).trim()))
                .findFirst()
                .orElseThrow();
        return Map.of(
                "cpuUsageUsec",
                usage,
                "memoryCurrentBytes",
                Long.parseLong(memory.getStdout().trim()),
                "lifetimeMemoryPeakBytes",
                Long.parseLong(peak.getStdout().trim()));
    }

    @SuppressWarnings("unchecked")
    private static <T> T transactional(T target, PlatformTransactionManager manager) {
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> checks() throws ReflectiveOperationException {
        var field = SettlementReconcileJdbcAdapter.class.getDeclaredField("CHECKS");
        field.setAccessible(true);
        return new LinkedHashMap<>((Map<String, String>) field.get(null));
    }

    static void seed(JdbcTemplate jdbc, int count) {
        seedRange(jdbc, 0, count);
    }

    static void seedRange(JdbcTemplate jdbc, int from, int count) {
        jdbc.update("""
                INSERT INTO p_payments(order_id,reservation_id,user_id,seller_id,performance_id,schedule_id,pg_provider,
                  expires_at,total_price,discount_amount,amount,status,approved_at,created_at,created_by,updated_at,updated_by)
                SELECT 'reconcile317-'||g,g,1,1+g%1000,3,4,'TOSS',timestamp '2026-10-19 10:10',10000,0,10000,
                  'DONE',timestamp '2026-10-19 10:00',LOCALTIMESTAMP,'measure',LOCALTIMESTAMP,'measure'
                FROM generate_series(?::bigint,?::bigint) g
                """, from + 1, from + count);
        jdbc.update("""
                INSERT INTO p_settlement_items(kind,status,seller_id,payment_id,performance_id,schedule_id,approved_at,
                  gross_amount,discount_amount,booking_fee,paid_amount,commission,commission_vat,seller_amount,
                  booking_fee_net,booking_fee_vat,policy_version,calendar_version,eligible_at,
                  created_at,created_by,updated_at,updated_by)
                SELECT 'SALE','PENDING',seller_id,id,performance_id,schedule_id,approved_at,10000,0,0,10000,
                  1200,120,8680,0,0,'measure','measure',timestamp '2026-11-12 04:00',
                  LOCALTIMESTAMP,'measure',LOCALTIMESTAMP,'measure' FROM p_payments WHERE id>? AND id<=?
                """, from, from + count);
        jdbc.update("""
                INSERT INTO p_ledger_transactions(source_type,source_id,action,seller_id,payment_id,policy_version,
                  occurred_at,created_at,created_by,updated_at,updated_by)
                SELECT 'SALE',id,'POST',seller_id,id,'measure',approved_at,
                  LOCALTIMESTAMP,'measure',LOCALTIMESTAMP,'measure' FROM p_payments WHERE id>? AND id<=?
                """, from, from + count);
        jdbc.update("""
                INSERT INTO p_ledger_entries(transaction_id,line_no,account,debit,credit,created_at,created_by,updated_at,updated_by)
                SELECT t.id,v.line_no,v.account,v.debit,v.credit,LOCALTIMESTAMP,'measure',LOCALTIMESTAMP,'measure'
                FROM p_ledger_transactions t CROSS JOIN (VALUES(1,'PG_CLEARING',10000,0),(2,'SELLER_PAYABLE',0,8680),
                  (3,'COMMISSION_REVENUE',0,1200),(4,'VAT_PAYABLE',0,120)) v(line_no,account,debit,credit)
                WHERE t.payment_id>? AND t.payment_id<=?
                """, from, from + count);
    }

    private static long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
