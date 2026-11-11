package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.payment.infrastructure.settlement.projection.ProjectionCheckpoint;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
class ProjectionCommitBoundaryTest {
    @TempDir
    Path temporary;

    @Test
    void realJvmStopsAtFiveExplicitCommitBoundariesAndReplaysWithoutDoubleCounting() throws Exception {
        Path output = Path.of(System.getProperty("projection.output")).resolve("boundaries");
        Files.createDirectories(output);
        var results = new ArrayList<java.util.Map<String, Object>>();
        try (var postgres = new PostgreSQLContainer<>("postgres:16");
                var kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"))
                        .withEnv("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1")
                        .withEnv("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1")
                        .withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1")
                        .withEnv("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0")) {
            postgres.start();
            kafka.start();
            for (var stage : ProjectionCheckpoint.Stage.values()) {
                Process child = null;
                var result = new LinkedHashMap<String, Object>();
                result.put("stage", stage.name());
                results.add(result);
                String schema = "boundary_" + stage.ordinal();
                try (var setup = ProjectionProcessMain.dataSource(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                    new JdbcTemplate(setup).execute("CREATE SCHEMA " + schema);
                }
                String url = postgres.getJdbcUrl() + (postgres.getJdbcUrl().contains("?") ? "&" : "?")
                        + "currentSchema=" + schema;
                try (var ds = ProjectionProcessMain.dataSource(url, postgres.getUsername(), postgres.getPassword())) {
                    try (var connection = ds.getConnection()) {
                        ScriptUtils.executeSqlScript(
                                connection, new FileSystemResource("../sql/payment-service/schema.sql"));
                    }
                    var jdbc = new JdbcTemplate(ds);
                    boolean source = stage == ProjectionCheckpoint.Stage.SOURCE_TX_COMMITTED;
                    if (!source)
                        new TransactionTemplate(new DataSourceTransactionManager(ds))
                                .executeWithoutResult(status -> ReconcilePerformanceTest.seed(jdbc, 30));
                    Path gate = temporary.resolve(stage.name() + ".gate");
                    Path state = temporary.resolve(stage.name() + "-state");
                    String prefix = "boundary." + UUID.randomUUID();
                    String mode = stage == ProjectionCheckpoint.Stage.B_DB_COMMITTED ? "B" : "C";
                    child = ProjectionProcessLauncher.start(
                            temporary,
                            url,
                            postgres.getUsername(),
                            postgres.getPassword(),
                            kafka.getBootstrapServers(),
                            state,
                            prefix,
                            output.resolve(stage.name() + "-kill.log"),
                            List.of(mode, stage.name(), gate.toString(), source ? "30" : "0"));
                    Process gated = child;
                    await(() -> Files.exists(gate), gated);
                    assertThat(Files.readString(gate)).isEqualTo(stage.name());
                    if (source)
                        assertThat(jdbc.queryForObject(
                                        "SELECT count(*) FROM p_settlement_projection_facts", Long.class))
                                .isEqualTo(30);
                    if (stage == ProjectionCheckpoint.Stage.PUBLISH_ACKNOWLEDGED)
                        assertThat(jdbc.queryForObject(
                                        "SELECT count(*) FROM p_settlement_projection_deliveries WHERE published_at IS NULL",
                                        Long.class))
                                .isPositive();
                    result.put("killedPid", gated.pid());
                    gated.destroyForcibly();
                    assertThat(gated.waitFor(10, TimeUnit.SECONDS)).isTrue();
                    result.put("exit", gated.exitValue());
                    long started = System.nanoTime();
                    child = ProjectionProcessLauncher.start(
                            temporary,
                            url,
                            postgres.getUsername(),
                            postgres.getPassword(),
                            kafka.getBootstrapServers(),
                            state,
                            prefix,
                            output.resolve(stage.name() + "-resume.log"),
                            List.of(mode, "NONE", gate.toString(), "0"));
                    Process resumed = child;
                    await(
                            () -> jdbc.queryForObject(
                                            "SELECT generation FROM p_settlement_projection_head WHERE id=1",
                                            Long.class)
                                    == 1,
                            resumed);
                    result.put("resumeToVerifiedActiveMs", (System.nanoTime() - started) / 1000000);
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM p_settlement_projection_applied", Long.class))
                            .isEqualTo(30);
                    assertThat(jdbc.queryForObject(
                                    "SELECT sum(sale_count) FROM p_settlement_projection_totals", Long.class))
                            .isEqualTo(30);
                    assertThat(jdbc.queryForObject(
                                    "SELECT sum(sale_amount) FROM p_settlement_projection_totals", Long.class))
                            .isEqualTo(260400);
                    assertThat(jdbc.queryForObject("SELECT sum(debit)-sum(credit) FROM p_ledger_entries", Long.class))
                            .isZero();
                    assertThat(jdbc.queryForObject(
                                    "SELECT count(*) FROM p_settlement_projection_errors WHERE NOT resolved",
                                    Long.class))
                            .isZero();
                    result.put("applied", 30);
                    result.put("sellerAmount", 260400);
                    result.put("passed", true);
                    resumed.destroyForcibly();
                    assertThat(resumed.waitFor(10, TimeUnit.SECONDS)).isTrue();
                    child = null;
                } finally {
                    if (child != null && child.isAlive()) {
                        child.destroyForcibly();
                        child.waitFor(10, TimeUnit.SECONDS);
                    }
                    new ObjectMapper()
                            .writerWithDefaultPrettyPrinter()
                            .writeValue(output.resolve("results.json").toFile(), results);
                }
            }
        }
    }

    private static void await(BooleanSupplier done, Process child) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        while (!done.getAsBoolean() && System.nanoTime() < deadline) {
            assertThat(child.isAlive()).isTrue();
            Thread.sleep(50);
        }
        assertThat(done.getAsBoolean()).as("커밋 경계 도달 또는 재개 완료").isTrue();
    }
}
