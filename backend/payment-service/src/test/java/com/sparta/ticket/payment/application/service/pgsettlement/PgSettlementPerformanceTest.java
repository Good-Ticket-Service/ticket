package com.sparta.ticket.payment.application.service.pgsettlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.payment.infrastructure.pg.PgProperties;
import com.sparta.ticket.payment.infrastructure.pgsettlement.PgSettlementBatchConfig;
import com.sparta.ticket.payment.infrastructure.pgsettlement.PgSettlementHttpClient;
import com.sparta.ticket.payment.infrastructure.pgsettlement.PgSettlementMetrics;
import com.sparta.ticket.payment.support.PaymentIntegrationTest;
import com.sparta.ticket.payment.support.pg.FakePgContainer;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;

/** M1 처리량과 M2 실제 JVM 강제 종료·재시작. 정규 build에서는 실행하지 않는다. */
@EnabledIfSystemProperty(named = "pg.settlement.measure", matches = "true")
class PgSettlementPerformanceTest extends PaymentIntegrationTest {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 21);
    private final int rows = Integer.getInteger("pg.settlement.rows", 500000);
    private final Path output = Path.of("build/pg-settlement-performance").toAbsolutePath();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JobRepository repository;

    @Autowired
    private JobExplorer explorer;

    @Autowired
    private PlatformTransactionManager manager;

    @Autowired
    private PgSettlementTransactions transactions;

    @Autowired
    private PgSettlementProperties properties;

    @Autowired
    private PgSettlementMetrics metrics;

    @Autowired
    private ObjectMapper json;

    @Test
    void measureThroughputAndRealProcessRestart() throws Exception {
        Files.createDirectories(output);
        seed();
        var base = FakePgContainer.baseUrl();
        var client = new PgSettlementHttpClient(
                new PgProperties(
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(10),
                        new PgProperties.Toss(base, "test_sk_fake_toss"),
                        new PgProperties.Kakao(
                                base,
                                "DEV_fake_kakao",
                                "TC0ONETIME",
                                "http://localhost/ok",
                                "http://localhost/cancel",
                                "http://localhost/fail")),
                RestClient.builder());
        var launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(repository);
        launcher.afterPropertiesSet();
        for (int size : List.of(500, 2000)) {
            resetSettlement();
            ManagementFactory.getMemoryPoolMXBeans().forEach(p -> p.resetPeakUsage());
            long start = System.nanoTime();
            var config = new PgSettlementProperties(properties.fees(), false, properties.startDate(), size, 1);
            var job = new PgSettlementBatchConfig()
                    .pgSettlementJob(repository, manager, client, transactions, config, paymentClock, metrics);
            var execution = launcher.run(
                    job,
                    new JobParametersBuilder()
                            .addString("provider", "TOSS")
                            .addString("settlementDate", DAY.toString())
                            .toJobParameters());
            assertThat(execution.getAllFailureExceptions()).isEmpty();
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            verify();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("rows", rows);
            result.put("pageSize", size);
            result.put("durationMs", (System.nanoTime() - start) / 1000000);
            result.put(
                    "peakHeapBytes",
                    ManagementFactory.getMemoryPoolMXBeans().stream()
                            .filter(p -> p.getType() == MemoryType.HEAP)
                            .mapToLong(p -> p.getPeakUsage().getUsed())
                            .sum());
            result.put(
                    "steps",
                    execution.getStepExecutions().stream()
                            .map(s -> Map.of(
                                    "name",
                                    s.getStepName(),
                                    "durationMs",
                                    Duration.between(s.getStartTime(), s.getEndTime())
                                            .toMillis(),
                                    "readCount",
                                    s.getReadCount(),
                                    "writeCount",
                                    s.getWriteCount()))
                            .toList());
            json.writerWithDefaultPrettyPrinter()
                    .writeValue(output.resolve("M1-" + size + ".json").toFile(), result);
        }
        resetSettlement();
        realProcessRestart();
    }

    private void seed() {
        new org.springframework.transaction.support.TransactionTemplate(manager)
                .executeWithoutResult(status -> seedInTransaction());
    }

    private void seedInTransaction() {
        jdbc.execute("TRUNCATE p_payments,p_pg_settlement_days,batch_job_instance CASCADE");
        jdbc.update("""
                INSERT INTO p_payments(order_id,reservation_id,user_id,seller_id,performance_id,schedule_id,pg_provider,
                  pg_transaction_id,expires_at,total_price,discount_amount,amount,status,approved_at,pg_approved_at,
                  pg_approved_amount,created_at,created_by,updated_at,updated_by)
                SELECT 'pg316-measure-'||lpad(g::text,9,'0'),31600000000+g,1,316501,3,4,'TOSS',
                  'pg316-measure-'||lpad(g::text,9,'0'),timestamp '2026-10-19 10:10',10000,0,10000,'DONE',
                  timestamp '2026-10-19 10:00',timestamp '2026-10-19 10:00',10000,LOCALTIMESTAMP,'measure',LOCALTIMESTAMP,'measure'
                FROM generate_series(1,?) g
                """, rows);
        jdbc.execute("""
                INSERT INTO p_ledger_transactions(source_type,source_id,action,seller_id,payment_id,policy_version,
                  occurred_at,created_at,created_by,updated_at,updated_by)
                SELECT 'SALE',id,'POST',seller_id,id,'2026-10-30-v1',approved_at,LOCALTIMESTAMP,'measure',LOCALTIMESTAMP,'measure'
                FROM p_payments WHERE order_id LIKE 'pg316-measure-%'
                """);
        jdbc.execute("""
                INSERT INTO p_ledger_entries(transaction_id,line_no,account,debit,credit,created_at,created_by,updated_at,updated_by)
                SELECT t.id,v.line_no,v.account,v.debit,v.credit,LOCALTIMESTAMP,'measure',LOCALTIMESTAMP,'measure'
                FROM p_ledger_transactions t CROSS JOIN (VALUES(1,'PG_CLEARING',10000,0),(2,'SELLER_PAYABLE',0,8680),
                    (3,'COMMISSION_REVENUE',0,1200),(4,'VAT_PAYABLE',0,120)) v(line_no,account,debit,credit)
                WHERE t.source_type='SALE'
                """);
        var fake = FakePgContainer.jdbc();
        fake.execute("TRUNCATE pg_transaction CASCADE");
        fake.update("""
                INSERT INTO pg_transaction(provider,payment_key,order_id,order_name,amount,status,auth_expires_at,requested_at,approved_at,updated_at)
                SELECT 'TOSS','pg316-measure-'||lpad(g::text,9,'0'),'pg316-measure-'||lpad(g::text,9,'0'),'명세 측정',10000,
                  'DONE',timestamp '2026-10-19 10:10',timestamp '2026-10-19 10:00',timestamp '2026-10-19 10:00',LOCALTIMESTAMP
                FROM generate_series(1,?) g
                """, rows);
        jdbc.execute("ANALYZE p_payments");
        jdbc.execute("ANALYZE p_ledger_transactions");
        fake.execute("ANALYZE pg_transaction");
    }

    private void resetSettlement() {
        com.sparta.ticket.payment.support.LedgerTestFixture.corrupt(
                jdbc,
                "DELETE FROM p_ledger_entries e USING p_ledger_transactions t WHERE t.id=e.transaction_id AND t.source_type IN ('PG_SETTLEMENT','PG_ADJUST')");
        com.sparta.ticket.payment.support.LedgerTestFixture.corrupt(
                jdbc, "DELETE FROM p_ledger_transactions WHERE source_type IN ('PG_SETTLEMENT','PG_ADJUST')");
        jdbc.execute("TRUNCATE p_pg_settlement_days,batch_job_instance CASCADE");
    }

    private void verify() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM p_pg_settlement_lines", Long.class))
                .isEqualTo(rows);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM p_pg_reconcile_results WHERE finding<>'MATCHED'", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM p_ledger_transactions WHERE source_type='PG_SETTLEMENT'", Long.class))
                .isEqualTo(1);
        assertThat(transactions.balances().bookCash()).isEqualTo((long) rows * 9648);
        assertThat(transactions.balances().pgClearing()).isZero();
    }

    private Process start(int port, String logName) throws Exception {
        String java =
                Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        var process = new ProcessBuilder(java, "-Xmx512m", "-jar", System.getProperty("payment.jar"));
        var env = process.environment();
        env.put("SPRING_PROFILES_ACTIVE", "measurement");
        env.put("SERVER_PORT", String.valueOf(port));
        env.put("SPRING_CLOUD_CONFIG_ENABLED", "false");
        env.put("EUREKA_CLIENT_ENABLED", "false");
        env.put("SPRING_DATASOURCE_URL", POSTGRES.getJdbcUrl());
        env.put("SPRING_DATASOURCE_USERNAME", POSTGRES.getUsername());
        env.put("SPRING_DATASOURCE_PASSWORD", POSTGRES.getPassword());
        env.put("SPRING_JPA_HIBERNATE_DDL_AUTO", "validate");
        env.put("SPRING_SQL_INIT_MODE", "never");
        env.put("SPRING_JPA_OPEN_IN_VIEW", "false");
        env.put("PG_TOSS_BASE_URL", FakePgContainer.baseUrl());
        env.put("PG_KAKAO_BASE_URL", FakePgContainer.baseUrl());
        env.put("PAYMENT_PG_PAGE_SIZE", "500");
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
        return process.redirectErrorStream(true)
                .redirectOutput(output.resolve(logName).toFile())
                .start();
    }

    private RestClient admin(int port) {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("X-User-Id", "316")
                .defaultHeader("X-Username", "measure")
                .defaultHeader("X-User-Role", "MASTER")
                .build();
    }

    private void ready(Process process, RestClient client) throws Exception {
        long until = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        while (System.nanoTime() < until) {
            if (!process.isAlive())
                throw new IllegalStateException("측정 JVM 기동 실패. build/pg-settlement-performance 로그 확인");
            try {
                client.get().uri("/actuator/health").retrieve().toBodilessEntity();
                return;
            } catch (org.springframework.web.client.RestClientException e) {
                Thread.sleep(250);
            }
        }
        throw new IllegalStateException("측정 JVM 기동 시간 초과");
    }

    private void realProcessRestart() throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        var admin = admin(port);
        var fakeAdmin = RestClient.create(FakePgContainer.baseUrl());
        fakeAdmin
                .put()
                .uri("/fake/admin/faults/TOSS/SETTLEMENT")
                .body(Map.of("latencyMs", 25))
                .retrieve()
                .toBodilessEntity();
        Process process = start(port, "M2-before.log");
        try {
            ready(process, admin);
            JsonNode started = admin.post()
                    .uri("/api/admin/settlements/pg/runs")
                    .body(Map.of("provider", "TOSS", "settlementDate", DAY.toString()))
                    .retrieve()
                    .body(JsonNode.class);
            long execution = started.path("data").path("executionId").asLong();
            long until = System.nanoTime() + Duration.ofMinutes(3).toNanos();
            while (jdbc.queryForObject("SELECT count(*) FROM p_pg_settlement_lines", Long.class)
                    < Math.min(rows / 2, 5000)) {
                if (System.nanoTime() > until) throw new IllegalStateException("중단 위치 도달 시간 초과");
                Thread.sleep(100);
            }
            process.destroyForcibly().waitFor();
            long committed = jdbc.queryForObject("SELECT count(*) FROM p_pg_settlement_lines", Long.class);
            long requestsBefore = fakeAdmin
                    .get()
                    .uri("/fake/admin/faults/stats")
                    .retrieve()
                    .body(JsonNode.class)
                    .path("TOSS.SETTLEMENT")
                    .path("requests")
                    .asLong();
            var old = explorer.getJobExecution(execution);
            assertThat(old.isRunning()).isTrue();
            var receive = old.getStepExecutions().stream()
                    .filter(s -> s.getStepName().equals("pgSettlementReceive"))
                    .findFirst()
                    .orElseThrow();
            String cursor = receive.getExecutionContext().getString("cursor");
            long resumeStart = System.nanoTime();
            process = start(port, "M2-after.log");
            ready(process, admin);
            var restarted = admin.post()
                    .uri("/api/admin/settlements/pg/executions/" + execution + "/recover")
                    .body(Map.of("reason", "측정 JVM 강제 종료 확인", "processTerminated", true))
                    .retrieve()
                    .body(JsonNode.class);
            long next = restarted.path("data").path("executionId").asLong();
            until = System.nanoTime() + Duration.ofMinutes(15).toNanos();
            while (true) {
                var state = explorer.getJobExecution(next);
                if (state.getStatus() == BatchStatus.COMPLETED) break;
                if (state.getStatus() == BatchStatus.FAILED || System.nanoTime() > until)
                    throw new IllegalStateException("재시작 실패. M2-after.log 확인");
                Thread.sleep(1000);
            }
            verify();
            long resumeRequests = fakeAdmin
                            .get()
                            .uri("/fake/admin/faults/stats")
                            .retrieve()
                            .body(JsonNode.class)
                            .path("TOSS.SETTLEMENT")
                            .path("requests")
                            .asLong()
                    - requestsBefore;
            long remainingPages = (rows - committed + 499) / 500;
            assertThat(resumeRequests).isEqualTo(remainingPages);
            json.writerWithDefaultPrettyPrinter()
                    .writeValue(
                            output.resolve("M2.json").toFile(),
                            Map.of(
                                    "rows",
                                    rows,
                                    "committedBeforeKill",
                                    committed,
                                    "savedCursor",
                                    cursor,
                                    "oldExecutionId",
                                    execution,
                                    "resumedExecutionId",
                                    next,
                                    "durationIncludingBootMs",
                                    (System.nanoTime() - resumeStart) / 1000000,
                                    "finalLines",
                                    rows,
                                    "settlementJournals",
                                    1,
                                    "resumePageRequests",
                                    resumeRequests,
                                    "bookCash",
                                    transactions.balances().bookCash()));
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
            fakeAdmin.delete().uri("/fake/admin/faults").retrieve().toBodilessEntity();
        }
    }
}
