package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.payment.PaymentServiceApplication;
import com.sparta.ticket.payment.application.dto.payment.ConfirmCommand;
import com.sparta.ticket.payment.application.service.payment.PaymentTransactions;
import com.sparta.ticket.payment.application.service.payment.SettlementJobWorker;
import com.sparta.ticket.payment.domain.model.pg.PgResult;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionCatalogJdbc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** #363 전용 장수명 DB에서 준비와 측정을 분리한다. PG 응답을 제어하는 내부 TX 시험이며 HTTP 성능이 아니다. */
public final class SettlementKafkaBenchmarkMain {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-11T09:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final String USER = "sk363_app";
    private static final String PASSWORD = "sk363_local_fixture";

    private SettlementKafkaBenchmarkMain() {}

    public static void main(String[] args) throws Exception {
        String command = args[0];
        String database = args[1];
        if (!database.matches("sk363_[a-z0-9_]{1,50}")) throw new IllegalArgumentException("전용 DB 이름이 필요하다");
        String url = "jdbc:postgresql://127.0.0.1:15463/" + database + "?currentSchema=payment_schema,public";
        Path output = Path.of(args[2]);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("database", database);
        report.put("command", command);
        report.put("clock", CLOCK.instant().toString());
        long started = System.nanoTime();
        try {
            if ("projection-base".equals(command)) {
                try (var ds = ProjectionProcessMain.dataSource(url, USER, PASSWORD)) {
                    var jdbc = new JdbcTemplate(ds);
                    SettlementKafkaProjectionFixture.prepare(jdbc, Long.parseLong(args[3]), args[4], report);
                    report.put("oracle", oracle(jdbc, Long.parseLong(args[3]), 0));
                }
            } else if ("materialize".equals(command)) {
                try (var ds = ProjectionProcessMain.dataSource(url, USER, PASSWORD)) {
                    var jdbc = new JdbcTemplate(ds);
                    var manager = new DataSourceTransactionManager(ds);
                    var catalog = ProjectionKafkaIntegrationTest.proxy(
                            new ProjectionCatalogJdbc(jdbc, ProjectionKafkaIntegrationTest.factBatch(jdbc)), manager);
                    var id = java.util.UUID.nameUUIDFromBytes(
                            database.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    catalog.create(id, "D", "sk363-fixture", "기준 이력 원천 보존");
                    report.put("materialized", catalog.materialize(id));
                    report.put("oracle", oracle(jdbc, Long.parseLong(args[3]), 0));
                }
            } else if (List.of("db", "kafka", "kafka-recovery").contains(command)) {
                measure(url, args, report);
            } else {
                throw new IllegalArgumentException("알 수 없는 명령");
            }
            report.put("success", true);
        } catch (Exception | LinkageError exception) {
            report.put("success", false);
            report.put("failureClass", exception.getClass().getName());
            throw exception;
        } finally {
            report.put("totalSeconds", seconds(started));
            Files.createDirectories(output.toAbsolutePath().getParent());
            new ObjectMapper()
                    .findAndRegisterModules()
                    .writerWithDefaultPrettyPrinter()
                    .writeValue(output.toFile(), report);
        }
    }

    private static void measure(String url, String[] args, Map<String, Object> report) throws Exception {
        long history = Long.parseLong(args[3]);
        int workers = Integer.parseInt(args[4]);
        int warm = Integer.parseInt(args[5]);
        int count = Integer.parseInt(args[6]);
        int rate = Integer.parseInt(args[7]);
        boolean kafka = args[0].startsWith("kafka");
        String pipeline = args.length > 8 ? args[8] : "FINANCIAL";
        boolean full = !"FINANCIAL".equals(pipeline);
        if (!List.of("FINANCIAL", "D", "B").contains(pipeline)) throw new IllegalArgumentException("집계 경로가 잘못됐다");
        report.put("pipeline", pipeline);
        if (!List.of(1, 2, 4).contains(workers) || warm < 0 || count < 1 || count > 30000 || rate < 1 || rate > 1000)
            throw new IllegalArgumentException("측정 범위를 벗어났다");
        report.put("workers", workers);
        report.put("warmCount", warm);
        report.put("measureCount", count);
        report.put("targetRatePerSecond", rate);
        report.put("workerDelayMillis", 1000);
        report.put("completionObservationIntervalMillis", 100);
        report.put(
                "scope",
                "실제 승인 접수·완료 TX → " + (kafka ? "Outbox/Kafka 금융 Consumer" : "DB 정산 워커")
                        + (full ? " → 예상 집계 " + pipeline : ". 집계 소비 제외") + ". PG HTTP 제외");
        var options = new ArrayList<>(List.of(
                "--spring.profiles.active=measurement",
                "--spring.cloud.config.enabled=false",
                "--eureka.client.enabled=false",
                "--spring.datasource.url=" + url,
                "--spring.datasource.username=" + USER,
                "--spring.datasource.password=" + PASSWORD,
                "--spring.datasource.hikari.maximum-pool-size=20",
                "--spring.datasource.hikari.minimum-idle=5",
                "--spring.datasource.hikari.connection-timeout=5000",
                "--spring.jpa.hibernate.ddl-auto=validate",
                "--spring.sql.init.mode=never",
                "--spring.jpa.open-in-view=false"));
        options.add("--payment.settlement.consumer-recovery.observation-enabled=false");
        options.add("--payment.settlement.consumer-recovery.replay-enabled=" + "kafka-recovery".equals(args[0]));
        for (String name : List.of(
                "payment.recovery",
                "payment.reconcile",
                "payment.recovery.refund",
                "payment.observation",
                "payment.settlement.worker",
                "payment.settlement.backfill",
                "payment.settlement.statement",
                "payment.settlement.payout",
                "payment.settlement.payout.recovery",
                "payment.settlement.reconcile",
                "payment.settlement.projection",
                "payment.pg",
                "payment.messaging.confirm",
                "payment.messaging.result-relay")) {
            if (!full || !"payment.settlement.projection".equals(name)) options.add("--" + name + ".enabled=false");
        }
        if (kafka) {
            options.addAll(List.of(
                    "--spring.kafka.bootstrap-servers=127.0.0.1:19063",
                    "--spring.kafka.admin.fail-fast=true",
                    "--payment.settlement.posting.transport=KAFKA",
                    "--payment.settlement.posting.kafka.enabled=true",
                    "--payment.settlement.posting.kafka.topic=sk363." + args[1] + ".posting.v1",
                    "--payment.settlement.posting.kafka.group=sk363." + args[1] + ".posting.v1",
                    "--payment.settlement.posting.kafka.concurrency=" + workers));
        }
        if (!kafka)
            options.addAll(List.of(
                    "--payment.settlement.posting.transport=DB", "--payment.settlement.posting.kafka.enabled=false"));
        if (full)
            options.addAll(List.of(
                    "--spring.kafka.bootstrap-servers=127.0.0.1:19063",
                    "--payment.settlement.projection.enabled=true",
                    "--payment.settlement.projection.mode=" + pipeline,
                    "--payment.settlement.projection.threads=1",
                    "--payment.settlement.projection.topic-prefix=sk363." + args[1] + ".projection"));
        var app = new SpringApplication(PaymentServiceApplication.class, Configuration.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        long boot = System.nanoTime();
        try (var context = app.run(options.toArray(String[]::new))) {
            report.put("bootSeconds", seconds(boot));
            var jdbc = context.getBean(JdbcTemplate.class);
            if (jdbc.queryForObject(
                            "SELECT count(*) FROM p_payments WHERE status<>'READY' AND id>?", Long.class, history)
                    != 0) throw new IllegalStateException("이미 사용한 측정 DB다. 새 복제본이 필요하다");
            report.put("oracleBefore", oracle(jdbc, history, 0));
            java.util.UUID build = full
                    ? jdbc.queryForObject(
                            "SELECT h.build_id FROM p_settlement_projection_head h JOIN p_settlement_projection_builds b ON b.id=h.build_id WHERE h.id=1 AND b.status='ACTIVE' AND b.mode=?",
                            java.util.UUID.class,
                            pipeline)
                    : null;
            report.put("projectionBuild", build);
            var scheduler = Executors.newScheduledThreadPool(workers);
            int recoveryPayments = 0;
            var worker = context.getBean(SettlementJobWorker.class);
            var executions = new ArrayList<java.util.concurrent.ScheduledFuture<?>>();
            try {
                if (kafka) {
                    var container = context.getBean(
                                    org.springframework.kafka.config.KafkaListenerEndpointRegistry.class)
                            .getListenerContainer("settlement-posting");
                    long assigned = System.nanoTime();
                    while ((container.getAssignedPartitions() == null
                                    || container.getAssignedPartitions().size() != 6)
                            && seconds(assigned) < 30) Thread.sleep(100);
                    if (container.getAssignedPartitions() == null
                            || container.getAssignedPartitions().size() != 6)
                        throw new IllegalStateException("금융 Consumer 6파티션 할당 실패");
                    report.put("assignmentSeconds", seconds(assigned));
                    report.put(
                            "assignedPartitions",
                            container.getAssignedPartitions().toString());
                } else {
                    for (int index = 0; index < workers; index++)
                        executions.add(
                                scheduler.scheduleWithFixedDelay(worker::runOnce, 0, 1000, TimeUnit.MILLISECONDS));
                }
                var payments = context.getBean(PaymentTransactions.class);
                var warmResult = new LinkedHashMap<String, Object>();
                report.put("warm", warmResult);
                load(jdbc, payments, history + 1, warm, Math.min(rate, 50), build, warmResult);
                report.put("databaseBefore", database(jdbc));
                report.put("jvmBefore", jvm());
                var measuredResult = new LinkedHashMap<String, Object>();
                report.put("measured", measuredResult);
                load(jdbc, payments, history + warm + 1, count, rate, build, measuredResult);
                report.put("databaseAfter", database(jdbc));
                report.put("jvmAfter", jvm());
                if (kafka && count >= 10) {
                    String topic =
                            context.getEnvironment().getRequiredProperty("payment.settlement.posting.kafka.topic");
                    String group =
                            context.getEnvironment().getRequiredProperty("payment.settlement.posting.kafka.group");
                    @SuppressWarnings("unchecked")
                    var template = (org.springframework.kafka.core.KafkaTemplate<String, String>)
                            context.getBean(org.springframework.kafka.core.KafkaTemplate.class);
                    var duplicates = jdbc.queryForList(
                            "SELECT payment_id,event_payload FROM p_settlement_jobs WHERE payment_id>=? AND payment_id<? ORDER BY id LIMIT ?",
                            history + warm + 1,
                            history + warm + count + 1,
                            count / 10);
                    for (var item : duplicates)
                        template.send(topic, item.get("payment_id").toString(), (String) item.get("event_payload"))
                                .get(5, TimeUnit.SECONDS);
                    try (var admin = org.apache.kafka.clients.admin.AdminClient.create(
                            Map.of("bootstrap.servers", "127.0.0.1:19063"))) {
                        var partitions = new java.util.HashMap<
                                org.apache.kafka.common.TopicPartition, org.apache.kafka.clients.admin.OffsetSpec>();
                        for (int partition = 0; partition < 6; partition++)
                            partitions.put(
                                    new org.apache.kafka.common.TopicPartition(topic, partition),
                                    org.apache.kafka.clients.admin.OffsetSpec.latest());
                        var ends = admin.listOffsets(partitions).all().get(5, TimeUnit.SECONDS);
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                        boolean caughtUp = false;
                        while (System.nanoTime() < deadline) {
                            var offsets = admin.listConsumerGroupOffsets(group)
                                    .partitionsToOffsetAndMetadata()
                                    .get(5, TimeUnit.SECONDS);
                            caughtUp = ends.entrySet().stream()
                                    .allMatch(entry -> offsets.containsKey(entry.getKey())
                                            && offsets.get(entry.getKey()).offset()
                                                    >= entry.getValue().offset());
                            if (caughtUp) break;
                            Thread.sleep(100);
                        }
                        if (!caughtUp) throw new IllegalStateException("중복 전달 후 offset 수렴 실패");
                        report.put("duplicateMessages", duplicates.size());
                        report.put("offsetsCaughtUp", true);
                    }
                }
                if ("kafka-recovery".equals(args[0]))
                    recoveryPayments = SettlementKafkaRecoveryScenario.run(
                            context, jdbc, history + warm + count + 1, LocalDateTime.now(CLOCK), report);
            } finally {
                executions.forEach(future -> future.cancel(false));
                scheduler.shutdown();
                if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) throw new IllegalStateException("워커 종료 시간 초과");
            }
            long verify = System.nanoTime();
            report.put("oracleAfter", oracle(jdbc, history, warm + count + recoveryPayments));
            if (full)
                report.put("projectionOracle", projectionOracle(jdbc, build, history + history / 10 + warm + count));
            report.put("verifySeconds", seconds(verify));
        }
    }

    private static void load(
            JdbcTemplate jdbc,
            PaymentTransactions payments,
            long first,
            int count,
            int rate,
            java.util.UUID build,
            Map<String, Object> result)
            throws Exception {
        result.put("count", count);
        result.put("completionTarget", build == null ? "FINANCIAL_DONE" : "PROJECTION_APPLIED");
        if (count == 0) return;
        Map<Long, Long> submitted = new ConcurrentHashMap<>();
        Map<Long, Long> completed = new ConcurrentHashMap<>();
        Map<Long, Long> projected = new ConcurrentHashMap<>();
        var lowWater = new java.util.concurrent.atomic.AtomicLong(first);
        var observerRows = new java.util.concurrent.atomic.LongAdder();
        var observerQueries = new java.util.concurrent.atomic.LongAdder();
        List<Double> txMillis = Collections.synchronizedList(new ArrayList<>());
        List<Double> dispatchDelayMillis = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger failed = new AtomicInteger();
        var pool = new java.util.concurrent.ThreadPoolExecutor(
                8, 8, 0, TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(count));
        var observer = Executors.newSingleThreadScheduledExecutor();
        long started = System.nanoTime();
        try {
            observer.scheduleWithFixedDelay(
                    () -> {
                        try {
                            long low = lowWater.get();
                            if (low >= first + count) return;
                            String query = build == null
                                    ? "SELECT payment_id,false FROM p_settlement_jobs WHERE payment_id>=? AND payment_id<? AND status='DONE'"
                                    : "SELECT j.payment_id,a.fact_key IS NOT NULL FROM p_settlement_jobs j LEFT JOIN p_settlement_projection_applied a ON a.build_id=? AND a.fact_key='v1:SALE:'||j.payment_id||':POST' WHERE j.payment_id>=? AND j.payment_id<? AND j.status='DONE'";
                            Object[] arguments = build == null
                                    ? new Object[] {low, first + count}
                                    : new Object[] {build, low, first + count};
                            observerQueries.increment();
                            jdbc.query(
                                    query,
                                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                                        long id = rs.getLong(1), now = System.nanoTime();
                                        completed.putIfAbsent(id, now);
                                        if (rs.getBoolean(2)) projected.putIfAbsent(id, now);
                                        observerRows.increment();
                                    },
                                    arguments);
                            var target = build == null ? completed : projected;
                            while (low < first + count && target.containsKey(low)) low++;
                            lowWater.set(low);
                        } catch (RuntimeException exception) {
                            failed.incrementAndGet();
                        }
                    },
                    0,
                    100,
                    TimeUnit.MILLISECONDS);
            for (int index = 0; index < count; index++) {
                long due = started + TimeUnit.SECONDS.toNanos(1) * index / rate;
                while (System.nanoTime() < due) LockSupport.parkNanos(due - System.nanoTime());
                long id = first + index;
                submitted.put(id, due);
                pool.execute(() -> {
                    long before = System.nanoTime();
                    dispatchDelayMillis.add((before - due) / 1_000_000.0);
                    try {
                        String key = "sk363-approved-" + id;
                        if (payments.claimApproval("sk363-ready-" + id, new ConfirmCommand(key, 10000))
                                .isEmpty()) throw new IllegalStateException("승인 실행권이 없다");
                        payments.complete(id, new PgResult.Approved(key, 10000, LocalDateTime.now(CLOCK)));
                        txMillis.add((System.nanoTime() - before) / 1_000_000.0);
                    } catch (RuntimeException exception) {
                        failed.incrementAndGet();
                    }
                });
            }
            result.put("dispatchSeconds", seconds(started));
            long inputEnded = System.nanoTime();
            pool.shutdown();
            if (!pool.awaitTermination(180, TimeUnit.SECONDS)) throw new IllegalStateException("승인 TX 실행 제한 초과");
            long drain = System.nanoTime();
            var target = build == null ? completed : projected;
            while (target.size() < count && seconds(inputEnded) < 180 && failed.get() == 0) Thread.sleep(100);
            result.put("postApprovalTxWaitSeconds", seconds(drain));
            result.put("drainSeconds", seconds(inputEnded));
            result.put("elapsedSeconds", seconds(started));
            result.put("completed", completed.size());
            result.put("projectionApplied", projected.size());
            result.put("observerQueries", observerQueries.sum());
            result.put("observerReturnedRows", observerRows.sum());
            result.put("failures", failed.get());
            result.put("offered", count);
            result.put("completionThroughputPerSecond", target.size() / seconds(started));
            result.put("approvalTxMillis", percentiles(txMillis));
            result.put("generatorDispatchDelayMillis", percentiles(dispatchDelayMillis));
            result.put(
                    "scheduledToObservedDoneMillis",
                    percentiles(completed.entrySet().stream()
                            .map(entry -> (entry.getValue() - submitted.get(entry.getKey())) / 1_000_000.0)
                            .toList()));
            if (build != null) {
                result.put(
                        "scheduledToObservedProjectionMillis",
                        percentiles(projected.entrySet().stream()
                                .map(entry -> (entry.getValue() - submitted.get(entry.getKey())) / 1_000_000.0)
                                .toList()));
                result.put(
                        "financialToObservedProjectionMillis",
                        percentiles(projected.entrySet().stream()
                                .map(entry -> (entry.getValue() - completed.get(entry.getKey())) / 1_000_000.0)
                                .toList()));
            }
            result.put(
                    "remaining",
                    jdbc.queryForList(
                            "SELECT status,count(*) AS count FROM p_settlement_jobs WHERE payment_id>=? AND payment_id<? GROUP BY status",
                            first,
                            first + count));
            if (failed.get() != 0 || target.size() != count)
                throw new IllegalStateException("측정 입력 또는 처리 미완료: " + result);
        } finally {
            observer.shutdownNow();
            pool.shutdownNow();
        }
    }

    static Map<String, Object> projectionOracle(JdbcTemplate jdbc, java.util.UUID build, long expected) {
        var result = new LinkedHashMap<String, Object>();
        result.put(
                "applied",
                jdbc.queryForObject(
                        "SELECT count(*) FROM p_settlement_projection_applied WHERE build_id=?", Long.class, build));
        result.put(
                "unapplied",
                jdbc.queryForObject(
                        "SELECT count(*) FROM p_settlement_projection_deliveries WHERE build_id=? AND NOT applied",
                        Long.class,
                        build));
        result.put(
                "pending",
                jdbc.queryForObject(
                        "SELECT count(*) FROM p_settlement_projection_payment_states WHERE build_id=? AND pending",
                        Long.class,
                        build));
        result.put(
                "errors",
                jdbc.queryForObject(
                        "SELECT count(*) FROM p_settlement_projection_errors WHERE build_id=? AND NOT resolved",
                        Long.class,
                        build));
        result.put("keyDifferences", ProjectionLoadDriver.keyDifferences(jdbc));
        result.put(
                "badFixtureEligibility",
                scalar(
                        jdbc,
                        "SELECT count(*) FROM p_settlement_items WHERE kind='SALE' AND (schedule_start_at IS DISTINCT FROM timestamp '2026-11-07 19:00' OR eligible_at IS DISTINCT FROM timestamp '2026-11-11 00:00')"));
        boolean valid = result.get("applied").equals(expected)
                && List.of("unapplied", "pending", "errors", "keyDifferences", "badFixtureEligibility").stream()
                        .allMatch(key -> result.get(key).equals(0L));
        result.put("valid", valid);
        if (!valid) throw new IllegalStateException("집계 독립 대조 실패: " + result);
        return result;
    }

    static Map<String, Object> oracle(JdbcTemplate jdbc, long history, long completed) {
        long refunds = history / 10;
        var values = new LinkedHashMap<String, Object>();
        values.put(
                "saleJournals",
                scalar(jdbc, "SELECT count(*) FROM p_ledger_transactions WHERE source_type='SALE' AND action='POST'"));
        values.put(
                "refundJournals",
                scalar(
                        jdbc,
                        "SELECT count(*) FROM p_ledger_transactions WHERE source_type='REFUND' AND action='REVERSE'"));
        values.put(
                "unbalancedJournals",
                scalar(
                        jdbc,
                        "SELECT count(*) FROM (SELECT t.id FROM p_ledger_transactions t LEFT JOIN p_ledger_entries e ON e.transaction_id=t.id GROUP BY t.id HAVING count(e.id)<>4 OR coalesce(sum(e.debit-e.credit),1)<>0) bad"));
        values.put(
                "badSaleAmounts",
                scalar(
                        jdbc,
                        "SELECT count(*) FROM p_settlement_items WHERE kind='SALE' AND (seller_amount<>8680 OR commission<>1200 OR commission_vat<>120 OR paid_amount<>10000 OR status NOT IN ('PENDING','CANCELED'))"));
        values.put(
                "badPaymentState",
                scalar(
                        jdbc,
                        "SELECT count(*) FROM p_settlement_items s JOIN p_payments p ON p.id=s.payment_id WHERE (s.status='CANCELED') IS DISTINCT FROM (p.status='CANCELED')"));
        values.put("doneJobs", scalar(jdbc, "SELECT count(*) FROM p_settlement_jobs WHERE status='DONE'"));
        values.put("unfinishedJobs", scalar(jdbc, "SELECT count(*) FROM p_settlement_jobs WHERE status<>'DONE'"));
        values.put("sourceFacts", scalar(jdbc, "SELECT count(*) FROM p_settlement_projection_facts"));
        values.put("refunds", scalar(jdbc, "SELECT count(*) FROM p_refunds WHERE status='DONE'"));
        values.put("saleItems", scalar(jdbc, "SELECT count(*) FROM p_settlement_items WHERE kind='SALE'"));
        values.put(
                "sellerPayableBalance",
                scalar(
                        jdbc,
                        "SELECT coalesce(sum(credit-debit),0) FROM p_ledger_entries WHERE account='SELLER_PAYABLE'"));
        boolean valid = values.get("saleJournals").equals(history + completed)
                && values.get("refundJournals").equals(refunds)
                && values.get("doneJobs").equals(history + refunds + completed)
                && values.get("sourceFacts").equals(history + refunds + completed)
                && values.get("refunds").equals(refunds)
                && values.get("saleItems").equals(history + completed)
                && values.get("sellerPayableBalance").equals((history - refunds + completed) * 8680)
                && List.of("unbalancedJournals", "badSaleAmounts", "badPaymentState", "unfinishedJobs").stream()
                        .allMatch(key -> values.get(key).equals(0L));
        values.put("valid", valid);
        if (!valid) throw new IllegalStateException("금융 기준 대조 실패: " + values);
        return values;
    }

    private static long scalar(JdbcTemplate jdbc, String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private static Map<String, Double> percentiles(List<Double> values) {
        if (values.isEmpty()) return Map.of();
        var sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        var result = new LinkedHashMap<String, Double>();
        for (int percentile : List.of(50, 95, 99, 100))
            result.put("p" + percentile, sorted.get((int) Math.ceil(sorted.size() * percentile / 100.0) - 1));
        return result;
    }

    private static Map<String, Object> database(JdbcTemplate jdbc) {
        return jdbc.queryForMap(
                "SELECT xact_commit,xact_rollback,blks_read,blks_hit,temp_bytes,deadlocks,blk_read_time,blk_write_time FROM pg_stat_database WHERE datname=current_database()");
    }

    private static Map<String, Object> jvm() {
        var bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        long cpu = bean instanceof com.sun.management.OperatingSystemMXBean os ? os.getProcessCpuTime() : -1;
        return Map.of(
                "cpuNanos",
                cpu,
                "heapUsedBytes",
                Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
    }

    private static double seconds(long started) {
        return (System.nanoTime() - started) / 1_000_000_000.0;
    }

    @TestConfiguration
    static class Configuration {
        @Bean
        @Primary
        Clock measurementClock() {
            return CLOCK;
        }
    }
}
