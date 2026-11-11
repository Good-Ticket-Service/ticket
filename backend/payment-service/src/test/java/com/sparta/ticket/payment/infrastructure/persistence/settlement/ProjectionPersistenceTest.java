package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.payment.domain.model.settlement.projection.ProjectionResult;
import com.sparta.ticket.payment.domain.model.settlement.projection.SettlementTotals;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionCatalogJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionConsumerJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionSinkJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.SettlementSourceJdbc;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

class ProjectionPersistenceTest {
    @Test
    void sourceManifestBatchConsumerVerificationAndGenerationFence() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16")) {
            postgres.start();
            var ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            try (var connection = ds.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new FileSystemResource("../sql/payment-service/schema.sql"));
            }
            var jdbc = new JdbcTemplate(ds);
            var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
            tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            var catalog = new ProjectionCatalogJdbc(jdbc, ProjectionKafkaIntegrationTest.factBatch(jdbc));
            var sink = new ProjectionSinkJdbc(jdbc);
            var consumer = new ProjectionConsumerJdbc(jdbc, sink);
            UUID build = UUID.randomUUID();
            tx.executeWithoutResult(status -> {
                ReconcilePerformanceTest.seed(jdbc, 12);
                catalog.create(build, "B", "test", "manifest and deduplication");
            });
            long materialized = tx.execute(status -> catalog.materialize(build));
            int firstFanout = tx.execute(status -> catalog.fanout(build));
            int secondFanout = tx.execute(status -> catalog.fanout(build));
            assertThat(materialized).isEqualTo(12);
            assertThat(firstFanout).isEqualTo(12);
            assertThat(secondFanout).isZero();
            var deliveries = tx.execute(status -> catalog.claim(100, 30000));
            assertThat(deliveries).hasSize(12);
            var claimedAgain = tx.execute(status -> catalog.claim(100, 30000));
            assertThat(claimedAgain).isEmpty();
            var facts = new SettlementSourceJdbc(jdbc).facts("");
            tx.executeWithoutResult(status -> {
                consumer.apply(build, facts);
                consumer.apply(build, facts);
                deliveries.forEach(catalog::published);
                catalog.freeze(build);
            });
            assertThat(jdbc.queryForObject("SELECT sum(sale_amount) FROM p_settlement_projection_totals", Long.class))
                    .isEqualTo(12 * 8680);
            boolean verified = tx.execute(status -> catalog.verify(build));
            long generation = tx.execute(status -> catalog.activate(build, 0));
            assertThat(verified).isTrue();
            assertThat(generation).isEqualTo(1);
            assertThatThrownBy(() -> tx.execute(status -> catalog.activate(build, 0)))
                    .isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM p_settlement_projection_applied", Long.class))
                    .isEqualTo(12);
            UUID replacement = UUID.randomUUID();
            tx.executeWithoutResult(status -> {
                catalog.create(replacement, "B", "test", "generation replacement");
                catalog.materialize(replacement);
                catalog.fanout(replacement);
                consumer.apply(replacement, facts);
                catalog.claim(100, 30000).forEach(catalog::published);
                catalog.freeze(replacement);
            });
            boolean replacementVerified = tx.execute(status -> catalog.verify(replacement));
            assertThat(replacementVerified).isTrue();
            long secondGeneration = tx.execute(status -> catalog.activate(replacement, 1));
            assertThat(secondGeneration).isEqualTo(2);
            UUID rollbackCommand = UUID.randomUUID();
            var rollback = tx.execute(status ->
                    catalog.control(rollbackCommand, build, "rollback", 2, "test", "restore previous pipeline"));
            assertThat(rollback.status()).isEqualTo("BUILDING");
            assertThat(catalog.generation()).isEqualTo(2);
            tx.executeWithoutResult(status -> {
                catalog.fanout(build);
                catalog.freeze(build);
            });
            boolean rollbackVerified = tx.execute(status -> catalog.verify(build));
            assertThat(rollbackVerified).isTrue();
            UUID activateCommand = UUID.randomUUID();
            var activated = tx.execute(
                    status -> catalog.control(activateCommand, build, "activate", 2, "test", "verified rollback"));
            var replayed = tx.execute(
                    status -> catalog.control(activateCommand, build, "activate", 2, "test", "verified rollback"));
            assertThat(replayed).isEqualTo(activated);
            assertThat(activated.generation()).isEqualTo(3);
            assertThatThrownBy(() -> tx.execute(
                            status -> catalog.control(activateCommand, build, "activate", 2, "test", "changed input")))
                    .isInstanceOf(com.sparta.ticket.common.exception.BusinessException.class);
            assertThatThrownBy(() -> jdbc.update("UPDATE p_settlement_projection_facts SET amount=0"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
    }

    @Test
    void absoluteSinkIgnoresOlderOutputAndDetectsEqualRevisionConflict() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16")) {
            postgres.start();
            var ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            try (var connection = ds.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new FileSystemResource("../sql/payment-service/schema.sql"));
            }
            var jdbc = new JdbcTemplate(ds);
            var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
            var sink = new ProjectionSinkJdbc(jdbc);
            UUID build = UUID.randomUUID();
            var catalog = new ProjectionCatalogJdbc(jdbc, ProjectionKafkaIntegrationTest.factBatch(jdbc));
            tx.executeWithoutResult(status -> {
                ReconcilePerformanceTest.seed(jdbc, 1001);
                catalog.create(build, "C", "test", "absolute sink");
            });
            tx.executeWithoutResult(status -> catalog.materialize(build));
            tx.executeWithoutResult(status -> catalog.fanout(build));
            var facts = new SettlementSourceJdbc(jdbc).facts(" AND t.payment_id IN (1,1001)");
            var first = facts.get(0);
            var second = facts.get(1);
            var latest = new ProjectionResult(
                    build.toString(),
                    "TOTAL",
                    second.aggregateKey(),
                    2,
                    new SettlementTotals(2, 17360, 0, 0),
                    second.sourceFactKey(),
                    second.meaningHash(),
                    null,
                    second.sellerAmount());
            var old = new ProjectionResult(
                    build.toString(),
                    "TOTAL",
                    first.aggregateKey(),
                    1,
                    new SettlementTotals(1, 8680, 0, 0),
                    first.sourceFactKey(),
                    first.meaningHash(),
                    null,
                    first.sellerAmount());
            var conflict = new ProjectionResult(
                    build.toString(),
                    "TOTAL",
                    latest.key(),
                    2,
                    old.totals(),
                    second.sourceFactKey(),
                    second.meaningHash(),
                    null,
                    second.sellerAmount());
            tx.executeWithoutResult(status -> sink.apply(List.of(latest, latest, old)));
            assertThat(jdbc.queryForObject("SELECT sale_amount FROM p_settlement_projection_totals", Long.class))
                    .isEqualTo(17360);
            tx.executeWithoutResult(status -> sink.apply(List.of(conflict)));
            assertThat(jdbc.queryForObject("SELECT reason FROM p_settlement_projection_errors", String.class))
                    .isEqualTo("TOTAL_REVISION_CONFLICT");
            assertThat(jdbc.queryForObject("SELECT sale_amount FROM p_settlement_projection_totals", Long.class))
                    .isEqualTo(17360);
        }
    }
}
