package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.SettlementFactJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.SettlementSourceJdbc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.Admin;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/** 두 JVM의 실제 partition 소유와 전체 중단 중 지속 수집을 확인한다. 브로커 HA 시험은 아니다. */
final class ProjectionAvailabilityScenario {
    private final Path temporary;

    ProjectionAvailabilityScenario(Path temporary) {
        this.temporary = temporary;
    }

    void tasksMigrateAndSixtySecondOutageRetainsContinuouslyCommittedFacts() throws Exception {
        Path output =
                Path.of(System.getProperty("projection.output")).resolve("availability-" + System.currentTimeMillis());
        Files.createDirectories(output);
        var report = new LinkedHashMap<String, Object>();
        report.put("phase", "BOOTSTRAP");
        report.put("scope", "two 512MiB heap engine JVMs; single retained broker/database; synthetic ledger commits");
        Process first = null, second = null, resumed = null;
        var collecting = new AtomicBoolean(true);
        var sourceFailure = new AtomicReference<Throwable>();
        var nextSale = new AtomicInteger(950);
        Thread ingress = null;
        try (var postgres = new PostgreSQLContainer<>("postgres:16");
                var kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"))
                        .withEnv("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1")
                        .withEnv("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1")
                        .withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1")
                        .withEnv("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0")) {
            postgres.start();
            kafka.start();
            try (var ds = ProjectionProcessMain.dataSource(
                            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                    var admin = Admin.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
                try {
                    try (var connection = ds.getConnection()) {
                        ScriptUtils.executeSqlScript(
                                connection, new FileSystemResource("../sql/payment-service/schema.sql"));
                    }
                    var jdbc = new JdbcTemplate(ds);
                    var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
                    tx.executeWithoutResult(status ->
                            ProjectionFixture.seed(jdbc, 0, 1000, true, ProjectionFixture.Distribution.UNIFORM));
                    String prefix = "availability." + UUID.randomUUID();
                    first = start(postgres, kafka, prefix, output, "first");
                    await(
                            () -> jdbc.queryForObject(
                                            "SELECT generation FROM p_settlement_projection_head WHERE id=1",
                                            Long.class)
                                    == 1,
                            first,
                            sourceFailure);
                    UUID build = jdbc.queryForObject(
                            "SELECT build_id FROM p_settlement_projection_head WHERE id=1", UUID.class);
                    String group = prefix + ".processor." + build;
                    report.put("phase", "TWO_MEMBER_ASSIGNMENT");
                    second = start(postgres, kafka, prefix, output, "second");
                    await(() -> assignedMembers(admin, group) == 2, second, sourceFailure);
                    report.put("concurrentAssignedMembers", assignedMembers(admin, group));
                    report.put("assignmentsBeforeKill", assignments(admin, group));

                    var source = new SettlementSourceJdbc(jdbc);
                    var facts = new SettlementFactJdbc(jdbc, source);
                    ingress = new Thread(
                            () -> {
                                try {
                                    while (collecting.get()) {
                                        long began = System.nanoTime();
                                        int from = nextSale.get();
                                        tx.executeWithoutResult(status -> {
                                            long floor = journalHead(jdbc);
                                            ProjectionFixture.seed(
                                                    jdbc, from, 100, false, ProjectionFixture.Distribution.UNIFORM);
                                            source.facts(" AND t.id>?", floor).forEach(facts::save);
                                        });
                                        nextSale.addAndGet(95);
                                        long remaining = 1_000_000_000L - (System.nanoTime() - began);
                                        if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining);
                                    }
                                } catch (InterruptedException exception) {
                                    Thread.currentThread().interrupt();
                                } catch (Throwable exception) {
                                    sourceFailure.set(exception);
                                }
                            },
                            "projection-continuous-ingress");
                    ingress.setDaemon(true);
                    ingress.start();
                    await(() -> nextSale.get() > 950, second, sourceFailure);
                    long migrationTarget = journalHead(jdbc);
                    report.put("phase", "SURVIVOR_MIGRATION");
                    long migration = System.nanoTime();
                    kill(first);
                    first = null;
                    await(
                            () -> assignedMembers(admin, group) == 1 && missing(jdbc, build, migrationTarget) == 0,
                            second,
                            sourceFailure);
                    report.put("killToSurvivorWatermarkMs", (System.nanoTime() - migration) / 1_000_000.0);
                    report.put("survivorAssignedMembers", assignedMembers(admin, group));
                    report.put("assignmentsAfterKill", assignments(admin, group));

                    kill(second);
                    second = null;
                    report.put("phase", "SIXTY_SECOND_OUTAGE");
                    long outage = System.nanoTime();
                    int before = nextSale.get();
                    while (System.nanoTime() - outage < Duration.ofSeconds(60).toNanos()) {
                        assertThat(sourceFailure.get()).isNull();
                        Thread.sleep(100);
                    }
                    long target = journalHead(jdbc);
                    report.put("actualOutageSeconds", (System.nanoTime() - outage) / 1_000_000_000.0);
                    report.put("factsCommittedDuringOutage", (nextSale.get() - before) / 95 * 100);
                    report.put("unappliedAtResume", missing(jdbc, build, target));
                    report.put("targetFactsPerSecond", 100);
                    assertThat(((Number) report.get("factsCommittedDuringOutage")).longValue())
                            .isPositive();
                    long restart = System.nanoTime();
                    report.put("phase", "CATCHUP_WITH_CONTINUOUS_INGRESS");
                    resumed = start(postgres, kafka, prefix, output, "resumed-fresh-state");
                    await(() -> missing(jdbc, build, target) == 0, resumed, sourceFailure);
                    report.put("restartToPreRestartWatermarkMs", (System.nanoTime() - restart) / 1_000_000.0);
                    report.put("ingressContinuedWhileCatchingUp", collecting.get());
                    collecting.set(false);
                    ingress.join(10000);
                    assertThat(ingress.isAlive()).isFalse();
                    assertThat(sourceFailure.get()).isNull();
                    long finalHead = journalHead(jdbc);
                    await(
                            () -> missing(jdbc, build, finalHead) == 0
                                    && ProjectionLoadDriver.keyDifferences(jdbc) == 0,
                            resumed,
                            sourceFailure);
                    report.put(
                            "finalFacts",
                            jdbc.queryForObject("SELECT count(*) FROM p_settlement_projection_facts", Long.class));
                    report.put("independentPerKeyDifferences", ProjectionLoadDriver.keyDifferences(jdbc));
                    report.put(
                            "financialDifference",
                            jdbc.queryForObject("SELECT sum(debit)-sum(credit) FROM p_ledger_entries", Long.class));
                    report.put(
                            "unresolvedErrors",
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM p_settlement_projection_errors WHERE NOT resolved",
                                    Long.class));
                    assertThat(report.get("financialDifference")).isEqualTo(0L);
                    assertThat(report.get("unresolvedErrors")).isEqualTo(0L);
                    report.put("passed", true);
                    report.put("phase", "COMPLETE");
                } finally {
                    collecting.set(false);
                    if (ingress != null) {
                        ingress.interrupt();
                        ingress.join(10000);
                    }
                    kill(first);
                    kill(second);
                    kill(resumed);
                }
            }
        } finally {
            new ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValue(output.resolve("report.json").toFile(), report);
        }
    }

    private Process start(
            PostgreSQLContainer<?> postgres, KafkaContainer kafka, String prefix, Path output, String name)
            throws Exception {
        return ProjectionProcessLauncher.start(
                temporary,
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword(),
                kafka.getBootstrapServers(),
                temporary.resolve(name),
                prefix,
                output.resolve(name + ".log"),
                List.of("C", "NONE", "unused", "0", "1000"));
    }

    private static long journalHead(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT coalesce(max(id),0) FROM p_ledger_transactions", Long.class);
    }

    private static long missing(JdbcTemplate jdbc, UUID build, long head) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM p_settlement_projection_facts f "
                        + "LEFT JOIN p_settlement_projection_applied a ON a.build_id=? AND a.fact_key=f.fact_key "
                        + "WHERE f.journal_id<=? AND a.fact_key IS NULL",
                Long.class,
                build,
                head);
    }

    private static long assignedMembers(Admin admin, String group) {
        try {
            var description = admin.describeConsumerGroups(List.of(group))
                    .all()
                    .get(5, TimeUnit.SECONDS)
                    .get(group);
            if (description.state() != org.apache.kafka.common.ConsumerGroupState.STABLE) return 0;
            return description.members().stream()
                    .filter(member -> !member.assignment().topicPartitions().isEmpty())
                    .count();
        } catch (Exception exception) {
            return -1;
        }
    }

    private static List<Map<String, Object>> assignments(Admin admin, String group) throws Exception {
        return admin.describeConsumerGroups(List.of(group)).all().get(5, TimeUnit.SECONDS).get(group).members().stream()
                .map(member -> Map.<String, Object>of(
                        "clientId", member.clientId(),
                        "partitions",
                                member.assignment().topicPartitions().stream()
                                        .map(Object::toString)
                                        .sorted()
                                        .toList()))
                .toList();
    }

    private static void await(BooleanSupplier done, Process process, AtomicReference<Throwable> failure)
            throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(180).toNanos();
        while (!done.getAsBoolean() && System.nanoTime() < deadline) {
            assertThat(process.isAlive()).as("집계 JVM 생존").isTrue();
            assertThat(failure.get()).as("연속 원천 수집").isNull();
            Thread.sleep(100);
        }
        assertThat(done.getAsBoolean()).as("180초 내 처리 담당 이동 또는 watermark 적용").isTrue();
    }

    private static void kill(Process process) throws InterruptedException {
        if (process == null || !process.isAlive()) return;
        process.destroyForcibly();
        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
    }
}
