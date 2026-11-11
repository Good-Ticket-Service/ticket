package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.SettlementFactJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.SettlementSourceJdbc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
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

@EnabledIfSystemProperty(named = "projection.recovery", matches = "true")
class ProjectionProcessRecoveryTest {
    @TempDir
    Path temporary;

    @Test
    void tasksMigrateAndContinuousIngressSurvivesSixtySecondOutage() throws Exception {
        new ProjectionAvailabilityScenario(temporary)
                .tasksMigrateAndSixtySecondOutageRetainsContinuouslyCommittedFacts();
    }

    @Test
    void forciblyKilledJvmRestoresCommittedStateAndFreshLocalDirectoryFromChangelog() throws Exception {
        Path output = Path.of(System.getProperty("projection.output"));
        Files.createDirectories(output);
        var report = new LinkedHashMap<String, Object>();
        report.put("scope", "real child JVM kill; PostgreSQL/Kafka retained; one broker; not broker HA");
        Process child = null;
        try (var postgres = new PostgreSQLContainer<>("postgres:16");
                var kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"))
                        .withEnv("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1")
                        .withEnv("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1")
                        .withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1")
                        .withEnv("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0")) {
            postgres.start();
            kafka.start();
            try (var ds = ProjectionProcessMain.dataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                report.put("connectionPoolMaximumPerJvm", 20);
                try (var connection = ds.getConnection()) {
                    ScriptUtils.executeSqlScript(
                            connection, new FileSystemResource("../sql/payment-service/schema.sql"));
                }
                var jdbc = new JdbcTemplate(ds);
                var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
                int rows = 10000;
                tx.executeWithoutResult(status -> ReconcilePerformanceTest.seed(jdbc, rows));
                String prefix = "recovery." + UUID.randomUUID();
                Path local = temporary.resolve("first-state");
                child = start(postgres, kafka, local, prefix, output.resolve("first-jvm.log"));
                Process first = child;
                await(
                        () -> jdbc.queryForObject("SELECT count(*) FROM p_settlement_projection_applied", Long.class)
                                > 0,
                        first);
                long appliedAtKill =
                        jdbc.queryForObject("SELECT count(*) FROM p_settlement_projection_applied", Long.class);
                assertThat(appliedAtKill).isLessThan(rows);
                report.put("firstPid", first.pid());
                report.put("appliedObservedBeforeKill", appliedAtKill);
                first.destroyForcibly();
                assertThat(first.waitFor(10, TimeUnit.SECONDS)).isTrue();
                report.put("firstExit", first.exitValue());
                long resume = System.nanoTime();
                child = start(postgres, kafka, local, prefix, output.resolve("resume-jvm.log"));
                Process resumed = child;
                await(
                        () -> jdbc.queryForObject(
                                        "SELECT generation FROM p_settlement_projection_head WHERE id=1", Long.class)
                                == 1,
                        resumed);
                report.put("resumeToVerifiedActiveMs", (System.nanoTime() - resume) / 1000000);
                UUID build =
                        jdbc.queryForObject("SELECT build_id FROM p_settlement_projection_head WHERE id=1", UUID.class);
                assertExact(jdbc, build, rows);
                resumed.destroyForcibly();
                assertThat(resumed.waitFor(10, TimeUnit.SECONDS)).isTrue();

                var source = new SettlementSourceJdbc(jdbc);
                var journal = new SettlementFactJdbc(jdbc, source);
                tx.executeWithoutResult(status -> {
                    ReconcilePerformanceTest.seedRange(jdbc, rows, 100);
                    source.facts(" AND t.payment_id>?", rows).forEach(journal::save);
                });
                long restore = System.nanoTime();
                child = start(
                        postgres, kafka, temporary.resolve("fresh-state"), prefix, output.resolve("changelog-jvm.log"));
                Process fresh = child;
                await(
                        () -> jdbc.queryForObject(
                                        "SELECT count(*) FROM p_settlement_projection_applied WHERE build_id=?",
                                        Long.class,
                                        build)
                                == rows + 100,
                        fresh);
                report.put("freshDirectoryToCatchupMs", (System.nanoTime() - restore) / 1000000);
                // Note: 최신 sink는 금액과 적용 증거를 같은 TX로 기록하며 최종 금액도 명시 확인한다.
                await(
                        () -> jdbc.queryForObject(
                                        "SELECT sum(sale_amount) FROM p_settlement_projection_totals WHERE build_id=?",
                                        Long.class,
                                        build)
                                == (rows + 100) * 8680L,
                        fresh);
                assertExact(jdbc, build, rows + 100);
                report.put("finalApplied", rows + 100);
                report.put("finalSellerAmount", (rows + 100) * 8680L);
                report.put(
                        "financialDifference",
                        jdbc.queryForObject("SELECT sum(debit)-sum(credit) FROM p_ledger_entries", Long.class));
                report.put(
                        "unresolvedErrors",
                        jdbc.queryForObject(
                                "SELECT count(*) FROM p_settlement_projection_errors WHERE NOT resolved", Long.class));
                assertThat(report.get("financialDifference")).isEqualTo(0L);
                assertThat(report.get("unresolvedErrors")).isEqualTo(0L);
                report.put("passed", true);
                fresh.destroyForcibly();
                assertThat(fresh.waitFor(10, TimeUnit.SECONDS)).isTrue();
                child = null;
            }
        } finally {
            if (child != null && child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(10, TimeUnit.SECONDS);
            }
            new ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValue(output.resolve("recovery.json").toFile(), report);
        }
    }

    private Process start(PostgreSQLContainer<?> postgres, KafkaContainer kafka, Path state, String prefix, Path log)
            throws Exception {
        Path args = temporary.resolve("args-" + UUID.randomUUID() + ".txt");
        var values = java.util.List.of(
                "-Xmx512m",
                "-cp",
                Files.readString(Path.of(System.getProperty("projection.test.classpath.file"))),
                ProjectionProcessMain.class.getName(),
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword(),
                kafka.getBootstrapServers(),
                state.toString(),
                prefix);
        Files.writeString(
                args,
                values.stream()
                        .map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                        .collect(java.util.stream.Collectors.joining("\n")));
        String java = Path.of(
                        System.getProperty("java.home"),
                        "bin",
                        System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java")
                .toString();
        return new ProcessBuilder(java, "@" + args)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
    }

    private static void await(BooleanSupplier done, Process process) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        while (!done.getAsBoolean() && System.nanoTime() < deadline) {
            assertThat(process.isAlive()).as("집계 JVM 생존").isTrue();
            Thread.sleep(50);
        }
        assertThat(done.getAsBoolean()).as("제한 시간 내 실제 프로세스 복구").isTrue();
    }

    private static void assertExact(JdbcTemplate jdbc, UUID build, int rows) {
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM p_settlement_projection_applied WHERE build_id=?", Long.class, build))
                .isEqualTo(rows);
        assertThat(jdbc.queryForObject(
                        "SELECT sum(sale_count) FROM p_settlement_projection_totals WHERE build_id=?",
                        Long.class,
                        build))
                .isEqualTo(rows);
        assertThat(jdbc.queryForObject(
                        "SELECT sum(sale_amount) FROM p_settlement_projection_totals WHERE build_id=?",
                        Long.class,
                        build))
                .isEqualTo(rows * 8680L);
    }
}
