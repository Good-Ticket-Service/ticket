package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionCatalogJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionFactBatchJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.SettlementFactJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.SettlementSourceJdbc;
import com.sparta.ticket.payment.infrastructure.settlement.projection.ProjectionJson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

class ProjectionBootstrapConcurrencyTest {
    @Test
    void independentBatchCommitReleasesDuplicateSourceBeforeTheReadSnapshotEnds() throws Exception {
        var results = new ArrayList<Map<String, Object>>();
        for (boolean independent : List.of(false, true)) {
            try (var postgres = new PostgreSQLContainer<>("postgres:16")) {
                postgres.start();
                try (var ds = ProjectionProcessMain.dataSource(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                    var jdbc = new JdbcTemplate(ds);
                    initialize(jdbc);
                    var manager = new DataSourceTransactionManager(ds);
                    var tx = new TransactionTemplate(manager);
                    UUID build = UUID.randomUUID();
                    var saved = new CountDownLatch(1);
                    var release = new CountDownLatch(1);
                    var once = new AtomicBoolean();
                    // Note: 비프록시 writer는 개선 전과 같이 전체 RR TX가 끝날 때 커밋된다.
                    var delegate = independent
                            ? ProjectionKafkaIntegrationTest.factBatch(jdbc)
                            : new ProjectionFactBatchJdbc(jdbc);
                    var paused = new ProjectionFactBatchJdbc(jdbc) {
                        @Override
                        public void save(List<Object[]> batch) {
                            delegate.save(batch);
                            if (!once.compareAndSet(false, true)) return;
                            saved.countDown();
                            try {
                                if (!release.await(15, TimeUnit.SECONDS))
                                    throw new IllegalStateException("시험의 초기 적재 일시 정지 시간이 초과됐다");
                            } catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(exception);
                            }
                        }
                    };
                    var catalog =
                            ProjectionKafkaIntegrationTest.proxy(new ProjectionCatalogJdbc(jdbc, paused), manager);
                    catalog.create(build, "B", "test", "bootstrap lock boundary");
                    var source = new SettlementSourceJdbc(jdbc);
                    var sale = source.facts(" AND t.payment_id=1").get(0);
                    var journal = new SettlementFactJdbc(jdbc, source);
                    var executor = Executors.newSingleThreadExecutor();
                    var loading = executor.submit(() -> catalog.materialize(build));
                    try {
                        assertThat(saved.await(10, TimeUnit.SECONDS)).isTrue();
                        long start = System.nanoTime();
                        RuntimeException failure = null;
                        try {
                            tx.executeWithoutResult(status -> {
                                jdbc.execute("SET LOCAL statement_timeout='1000ms'");
                                jdbc.queryForObject("SELECT id FROM p_payments WHERE id=1 FOR UPDATE", Long.class);
                                journal.save(sale);
                            });
                        } catch (RuntimeException exception) {
                            failure = exception;
                        }
                        double elapsed = (System.nanoTime() - start) / 1_000_000.0;
                        results.add(Map.of(
                                "independentBatch",
                                independent,
                                "elapsedMs",
                                elapsed,
                                "statementTimeoutMs",
                                1000,
                                "failed",
                                failure != null,
                                "bootstrapStillPaused",
                                release.getCount() == 1));
                        if (independent) assertThat(failure).isNull();
                        else assertThat(failure).hasMessageContaining("statement timeout");
                    } finally {
                        release.countDown();
                        executor.shutdown();
                    }
                    assertThat(loading.get(30, TimeUnit.SECONDS)).isEqualTo(2000);
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM p_settlement_projection_facts", Long.class))
                            .isEqualTo(2000);
                    assertThat(jdbc.queryForObject("SELECT sum(debit)-sum(credit) FROM p_ledger_entries", Long.class))
                            .isZero();
                }
            }
        }
        Path output = Path.of("build/reports/projection-bootstrap-lock.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, ProjectionJson.write(results));
    }

    @Test
    void interruptedCaptureKeepsValidCommittedChunksAndRetryDoesNotDuplicateFacts() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16")) {
            postgres.start();
            try (var ds = ProjectionProcessMain.dataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                var jdbc = new JdbcTemplate(ds);
                initialize(jdbc);
                var manager = new DataSourceTransactionManager(ds);
                var delegate = ProjectionKafkaIntegrationTest.factBatch(jdbc);
                var interrupted = new ProjectionFactBatchJdbc(jdbc) {
                    @Override
                    public void save(List<Object[]> batch) {
                        delegate.save(batch);
                        throw new IllegalStateException("첫 batch 커밋 직후 중단");
                    }
                };
                UUID build = UUID.randomUUID();
                var failed =
                        ProjectionKafkaIntegrationTest.proxy(new ProjectionCatalogJdbc(jdbc, interrupted), manager);
                failed.create(build, "B", "test", "retry interrupted capture");
                assertThatThrownBy(() -> failed.materialize(build))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("첫 batch");
                assertThat(jdbc.queryForObject("SELECT count(*) FROM p_settlement_projection_facts", Long.class))
                        .isEqualTo(1000);
                assertThat(jdbc.queryForObject(
                                "SELECT source_snapshot IS NULL FROM p_settlement_projection_builds WHERE id=?",
                                Boolean.class,
                                build))
                        .isTrue();
                var retried = ProjectionKafkaIntegrationTest.proxy(new ProjectionCatalogJdbc(jdbc, delegate), manager);
                assertThat(retried.materialize(build)).isEqualTo(2000);
                assertThat(retried.fanout(build)).isEqualTo(2000);
                assertThat(retried.fanout(build)).isZero();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM p_settlement_projection_facts", Long.class))
                        .isEqualTo(2000);
            }
        }
    }

    private static void initialize(JdbcTemplate jdbc) throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("../sql/payment-service/schema.sql"));
        }
        new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()))
                .executeWithoutResult(status -> ReconcilePerformanceTest.seed(jdbc, 2000));
    }
}
