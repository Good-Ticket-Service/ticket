package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionCatalogJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionConsumerJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionDbWorker;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionSinkJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.SettlementFactJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.SettlementSourceJdbc;
import com.sparta.ticket.payment.infrastructure.settlement.projection.ProjectionCheckpoint;
import com.sparta.ticket.payment.infrastructure.settlement.projection.ProjectionProperties;
import com.sparta.ticket.payment.infrastructure.settlement.projection.ProjectionRuntime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

class ProjectionDbWorkerTest {
    @Test
    void atomicBoundedWorkerRecoversAndContinuesWithoutKafka() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16")) {
            postgres.start();
            var ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            try (var connection = ds.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new FileSystemResource("../sql/payment-service/schema.sql"));
            }
            var jdbc = new JdbcTemplate(ds);
            var manager = new DataSourceTransactionManager(ds);
            var tx = new TransactionTemplate(manager);
            var catalog = ProjectionKafkaIntegrationTest.proxy(
                    new ProjectionCatalogJdbc(jdbc, ProjectionKafkaIntegrationTest.factBatch(jdbc)), manager);
            var sink = ProjectionKafkaIntegrationTest.proxy(new ProjectionSinkJdbc(jdbc), manager);
            var applier = ProjectionKafkaIntegrationTest.proxy(new ProjectionConsumerJdbc(jdbc, sink), manager);
            var worker = ProjectionKafkaIntegrationTest.proxy(new ProjectionDbWorker(jdbc, applier), manager);
            tx.executeWithoutResult(
                    status -> ProjectionFixture.seed(jdbc, 0, 100, true, ProjectionFixture.Distribution.UNIFORM));
            UUID build = catalog.create(UUID.randomUUID(), "D", "test", "DB engine atomicity");
            catalog.materialize(build);
            catalog.fanout(build);
            jdbc.execute(
                    "ALTER TABLE p_settlement_projection_deliveries ADD CONSTRAINT injected_db_completion CHECK(published_at IS NULL)");
            try {
                assertThatThrownBy(() -> worker.process(build, 32)).isInstanceOf(RuntimeException.class);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM p_settlement_projection_applied", Long.class))
                        .isZero();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM p_settlement_projection_totals", Long.class))
                        .isZero();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM p_settlement_projection_event_ids", Long.class))
                        .isZero();
            } finally {
                jdbc.execute("ALTER TABLE p_settlement_projection_deliveries DROP CONSTRAINT injected_db_completion");
            }
            tx.executeWithoutResult(status -> {
                jdbc.queryForMap("SELECT id FROM p_settlement_projection_builds WHERE id=? FOR UPDATE", build);
                try {
                    assertThat(CompletableFuture.supplyAsync(() -> worker.process(build, 32))
                                    .get(3, TimeUnit.SECONDS))
                            .isZero();
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            });
            assertThat(worker.process(build, 32)).isEqualTo(32);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM p_settlement_projection_deliveries WHERE published_at IS NOT NULL",
                            Long.class))
                    .isEqualTo(32);
            var config = new ProjectionProperties();
            assertThat(config.getMode()).isEqualTo("D");
            config.setBatchSize(32);
            try (var runtime = runtime(config, catalog, applier, sink, worker)) {
                for (int tick = 0; tick < 6 && catalog.generation() == 0; tick++) runtime.tick();
                assertThat(catalog.generation()).isEqualTo(1);
                assertNoKafka(runtime);
            }
            assertThat(worker.process(build, 32)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM p_settlement_projection_applied", Long.class))
                    .isEqualTo(100);
            // Note: 실행기를 닫은 사이의 추가 금융 사실을 새 실행기가 같은 세대에 정확히 한 번 반영한다.
            var source = new SettlementSourceJdbc(jdbc);
            var journal = new SettlementFactJdbc(jdbc, source);
            tx.executeWithoutResult(status -> {
                ProjectionFixture.seed(jdbc, 95, 100, false, ProjectionFixture.Distribution.UNIFORM);
                source.facts("").forEach(journal::save);
            });
            try (var restarted = runtime(config, catalog, applier, sink, worker)) {
                restarted.tick();
                restarted.tick();
                assertNoKafka(restarted);
                assertThat(catalog.generation()).isEqualTo(1);
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM p_settlement_projection_applied", Long.class))
                    .isEqualTo(200);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM p_settlement_projection_errors", Long.class))
                    .isZero();
            assertThat(ProjectionLoadDriver.keyDifferences(jdbc)).isZero();
            assertThat(jdbc.queryForObject("SELECT sum(debit)-sum(credit) FROM p_ledger_entries", Long.class))
                    .isZero();
        }
    }

    private ProjectionRuntime runtime(
            ProjectionProperties config,
            ProjectionCatalogJdbc catalog,
            ProjectionConsumerJdbc applier,
            ProjectionSinkJdbc sink,
            ProjectionDbWorker worker) {
        return new ProjectionRuntime(
                config, catalog, applier, sink, "invalid-kafka-address", ProjectionCheckpoint.NONE, null, worker);
    }

    private void assertNoKafka(ProjectionRuntime runtime) {
        assertThat(ReflectionTestUtils.getField(runtime, "producer")).isNull();
        assertThat(ReflectionTestUtils.getField(runtime, "sinkTask")).isNull();
        assertThat((java.util.Map<?, ?>) ReflectionTestUtils.getField(runtime, "processors"))
                .isEmpty();
    }
}
