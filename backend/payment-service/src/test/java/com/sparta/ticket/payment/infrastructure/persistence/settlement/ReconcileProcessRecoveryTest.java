package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.testcontainers.containers.PostgreSQLContainer;

/** 별도 JVM 두 개의 중복 요청과 업무 커밋 전후 강제 종료를 실제 운영 API로 검증한다. */
@EnabledIfSystemProperty(named = "reconcile.recovery", matches = "true")
class ReconcileProcessRecoveryTest {
    private final Path output = Path.of(System.getProperty("reconcile.output", "build/reconcile-recovery"))
            .toAbsolutePath();
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    void twoProcessesAndRestartBeforeAndAfterBusinessCommit() throws Exception {
        Files.createDirectories(output);
        try (var postgres = new PostgreSQLContainer<>("postgres:16")) {
            postgres.start();
            var ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            new ResourceDatabasePopulator(new FileSystemResource("../sql/payment-service/schema.sql")).execute(ds);
            var jdbc = new JdbcTemplate(ds);
            List<Map<String, Object>> results = new ArrayList<>();
            if (!System.getProperty("baseline.payment.jar", "").isBlank()) {
                reset(jdbc, ds);
                results.add(automaticProcesses(postgres, jdbc, true));
                reset(jdbc, ds);
                results.add(automaticProcesses(postgres, jdbc, false));
            }
            reset(jdbc, ds);
            results.add(duplicateProcesses(postgres, jdbc));
            for (boolean afterCommit : List.of(false, true)) {
                reset(jdbc, ds);
                results.add(killAndRecover(postgres, jdbc, ds, afterCommit));
                json.writerWithDefaultPrettyPrinter()
                        .writeValue(output.resolve("process-recovery.json").toFile(), results);
            }
        }
    }

    private void reset(JdbcTemplate jdbc, DriverManagerDataSource ds) {
        jdbc.execute(
                "TRUNCATE p_payments,p_payouts,p_settlement_statements,p_pg_settlement_days,p_settlement_reconcile_runs,p_reconcile_checkpoint,p_reconcile_account_balances,p_reconcile_commands,batch_job_instance RESTART IDENTITY CASCADE");
        jdbc.update("INSERT INTO p_reconcile_checkpoint(id) VALUES(1)");
        new TransactionTemplate(new DataSourceTransactionManager(ds))
                .executeWithoutResult(status -> ReconcilePerformanceTest.seed(jdbc, 3));
    }

    private Map<String, Object> duplicateProcesses(PostgreSQLContainer<?> postgres, JdbcTemplate jdbc)
            throws Exception {
        int firstPort = port();
        int secondPort = port();
        Process first = start(postgres, firstPort, "duplicate-a.log");
        Process second = null;
        var pool = Executors.newFixedThreadPool(2);
        try {
            ready(first, admin(firstPort));
            second = start(postgres, secondPort, "duplicate-b.log");
            ready(second, admin(secondPort));
            UUID command = UUID.randomUUID();
            var a = pool.submit(() -> requestStatus(admin(firstPort), command));
            var b = pool.submit(() -> requestStatus(admin(secondPort), command));
            var statuses = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
            await(() -> Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM batch_job_execution WHERE status='COMPLETED')", Boolean.class)));
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM batch_job_instance WHERE job_name='reconcileJob'", Long.class))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT generation FROM p_reconcile_checkpoint", Long.class))
                    .isEqualTo(1);
            return Map.of(
                    "scenario",
                    "two-process-duplicate",
                    "httpStatuses",
                    statuses,
                    "jobInstances",
                    1,
                    "acceptedGeneration",
                    1);
        } finally {
            pool.shutdownNow();
            stop(first);
            if (second != null) stop(second);
        }
    }

    private int requestStatus(RestClient client, UUID command) {
        try {
            request(client, command);
            return 200;
        } catch (RestClientResponseException e) {
            return e.getStatusCode().value();
        }
    }

    private long request(RestClient client, UUID command) {
        return client.post()
                .uri("/api/admin/settlements/reconciliation/runs")
                .body(Map.of(
                        "businessDate",
                        "2026-11-09",
                        "mode",
                        "FULL",
                        "commandId",
                        command.toString(),
                        "reason",
                        "실제 JVM 측정"))
                .retrieve()
                .body(JsonNode.class)
                .path("data")
                .path("executionId")
                .asLong();
    }

    private Map<String, Object> killAndRecover(
            PostgreSQLContainer<?> postgres, JdbcTemplate jdbc, DriverManagerDataSource ds, boolean afterCommit)
            throws Exception {
        int port = port();
        var client = admin(port);
        String label = afterCommit ? "after-business-commit" : "before-business-commit";
        Process process = start(postgres, port, label + "-before.log");
        try (var headLock = ds.getConnection();
                var stepLock = ds.getConnection()) {
            stepLock.setAutoCommit(false);
            ready(process, client);
            headLock.setAutoCommit(false);
            execute(headLock, "SELECT id FROM p_reconcile_checkpoint WHERE id=1 FOR UPDATE");
            long execution = request(client, UUID.randomUUID());
            await(() -> Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE wait_event_type='Lock'
                      AND query LIKE '%UPDATE p_reconcile_checkpoint%')
                    """, Boolean.class)));
            if (afterCommit) {
                long step = jdbc.queryForObject(
                        "SELECT step_execution_id FROM batch_step_execution WHERE job_execution_id=? AND step_name='reconcileDatabase'",
                        Long.class,
                        execution);
                stepLock.setAutoCommit(false);
                execute(
                        stepLock,
                        "SELECT step_execution_id FROM batch_step_execution WHERE step_execution_id=" + step
                                + " FOR UPDATE");
                headLock.commit();
                await(() -> jdbc.queryForObject(
                                "SELECT count(*) FROM p_settlement_reconcile_runs WHERE db_valid", Long.class)
                        == 1);
            }
            process.destroyForcibly().waitFor(15, TimeUnit.SECONDS);
            assertThat(process.isAlive()).isFalse();
            headLock.rollback();
            stepLock.rollback();
            long generationBefore = jdbc.queryForObject("SELECT generation FROM p_reconcile_checkpoint", Long.class);
            assertThat(generationBefore).isEqualTo(afterCommit ? 1 : 0);
            assertThat(jdbc.queryForObject(
                            "SELECT status FROM batch_job_execution WHERE job_execution_id=?", String.class, execution))
                    .isEqualTo("STARTED");
            long started = System.nanoTime();
            process = start(postgres, port, label + "-after.log");
            ready(process, client);
            long resumed = client.post()
                    .uri("/api/admin/settlements/reconciliation/executions/" + execution + "/recover")
                    .body(Map.of("reason", "측정 JVM 종료 확인", "processTerminated", true))
                    .retrieve()
                    .body(JsonNode.class)
                    .path("data")
                    .path("executionId")
                    .asLong();
            await(() -> "COMPLETED"
                    .equals(jdbc.queryForObject(
                            "SELECT status FROM batch_job_execution WHERE job_execution_id=?", String.class, resumed)));
            long duration = (System.nanoTime() - started) / 1000000;
            assertThat(jdbc.queryForObject("SELECT generation FROM p_reconcile_checkpoint", Long.class))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM p_settlement_reconcile_runs WHERE db_valid", Long.class))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject(
                            "SELECT sum(credit-debit) FROM p_reconcile_account_balances WHERE account='SELLER_PAYABLE'",
                            Long.class))
                    .isEqualTo(26040);
            return Map.of(
                    "scenario",
                    label,
                    "oldExecution",
                    execution,
                    "resumedExecution",
                    resumed,
                    "generationBeforeRecovery",
                    generationBefore,
                    "generationAfterRecovery",
                    1,
                    "acceptedRuns",
                    1,
                    "sellerPayable",
                    26040,
                    "recoveryIncludingBootMs",
                    duration);
        } finally {
            stop(process);
        }
    }

    private Process start(PostgreSQLContainer<?> postgres, int port, String log) throws Exception {
        return start(postgres, port, log, false, false);
    }

    private Process start(PostgreSQLContainer<?> postgres, int port, String log, boolean automatic, boolean baseline)
            throws Exception {
        var java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        var builder = new ProcessBuilder(
                java.toString(),
                "-Xmx384m",
                "-jar",
                System.getProperty(baseline ? "baseline.payment.jar" : "payment.jar"));
        var env = builder.environment();
        env.put("SPRING_PROFILES_ACTIVE", "measurement");
        env.put("SERVER_PORT", String.valueOf(port));
        env.put("SPRING_CLOUD_CONFIG_ENABLED", "false");
        env.put("EUREKA_CLIENT_ENABLED", "false");
        env.put("SPRING_DATASOURCE_URL", postgres.getJdbcUrl());
        env.put("SPRING_DATASOURCE_USERNAME", postgres.getUsername());
        env.put("SPRING_DATASOURCE_PASSWORD", postgres.getPassword());
        env.put("SPRING_JPA_HIBERNATE_DDL_AUTO", "validate");
        env.put("SPRING_SQL_INIT_MODE", "never");
        env.put("SPRING_JPA_OPEN_IN_VIEW", "false");
        for (String name : List.of(
                "PAYMENT_RECOVERY_ENABLED",
                "PAYMENT_RECONCILE_ENABLED",
                "PAYMENT_RECOVERY_REFUND_ENABLED",
                "PAYMENT_OBSERVATION_ENABLED",
                "PAYMENT_SETTLEMENT_WORKER_ENABLED",
                "PAYMENT_SETTLEMENT_BACKFILL_ENABLED",
                "PAYMENT_SETTLEMENT_STATEMENT_ENABLED",
                "PAYMENT_SETTLEMENT_PAYOUT_ENABLED",
                "PAYMENT_SETTLEMENT_PAYOUT_RECOVERY_ENABLED",
                "PAYMENT_SETTLEMENT_RECONCILE_ENABLED",
                "PAYMENT_PG_ENABLED")) env.put(name, "false");
        if (automatic) {
            env.put("PAYMENT_SETTLEMENT_RECONCILE_ENABLED", "true");
            env.put("PAYMENT_SETTLEMENT_RECONCILE_STARTUP_DELAY_MS", "1000");
        }
        return builder.redirectErrorStream(true)
                .redirectOutput(output.resolve(log).toFile())
                .start();
    }

    private Map<String, Object> automaticProcesses(PostgreSQLContainer<?> postgres, JdbcTemplate jdbc, boolean baseline)
            throws Exception {
        String label = baseline ? "M0-auto" : "M2-auto";
        int firstPort = port();
        int secondPort = port();
        var first = start(postgres, firstPort, label + "-a.log", true, baseline);
        Process second = null;
        try {
            ready(first, admin(firstPort));
            second = start(postgres, secondPort, label + "-b.log", true, baseline);
            ready(second, admin(secondPort));
            long expected = baseline ? 2 : 1;
            await(() -> jdbc.queryForObject(
                            "SELECT count(*) FROM p_settlement_reconcile_runs WHERE status='SUCCEEDED'", Long.class)
                    >= expected);
            Thread.sleep(2000);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM p_settlement_reconcile_runs", Long.class))
                    .isEqualTo(expected);
            return Map.of(
                    "scenario",
                    label,
                    "instances",
                    2,
                    "successfulDatabaseRuns",
                    expected,
                    "sqlChecks",
                    22 * expected,
                    "financialViolations",
                    jdbc.queryForObject("SELECT sum(violations) FROM p_settlement_reconcile_runs", Long.class));
        } finally {
            stop(first);
            if (second != null) stop(second);
        }
    }

    private static RestClient admin(int port) {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("X-User-Id", "317")
                .defaultHeader("X-Username", "measure")
                .defaultHeader("X-User-Role", "MASTER")
                .build();
    }

    private static int port() throws Exception {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void stop(Process process) throws Exception {
        if (process.isAlive()) {
            process.destroyForcibly().waitFor(15, TimeUnit.SECONDS);
        }
    }

    private static void ready(Process process, RestClient client) throws Exception {
        await(() -> {
            if (!process.isAlive()) throw new IllegalStateException("측정 JVM 기동 실패. 결과 폴더 로그 확인");
            try {
                client.get().uri("/actuator/health").retrieve().toBodilessEntity();
                return true;
            } catch (RestClientException e) {
                return false;
            }
        });
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("복구 시험 대기 시간 초과");
            Thread.sleep(100);
        }
    }
}
