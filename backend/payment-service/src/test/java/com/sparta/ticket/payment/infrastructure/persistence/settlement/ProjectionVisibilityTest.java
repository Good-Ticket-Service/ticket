package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionCatalogJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionConsumerJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionSinkJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.SettlementFactJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.SettlementSourceJdbc;
import com.sparta.ticket.payment.support.LedgerTestFixture;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

class ProjectionVisibilityTest {
    @Test
    void mixedFixtureLegacyHeadersAndOlderUncommittedWriterAreIncludedExactlyOnce() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16")) {
            postgres.start();
            try (var ds = ProjectionProcessMain.dataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                try (var connection = ds.getConnection()) {
                    ScriptUtils.executeSqlScript(
                            connection, new FileSystemResource("../sql/payment-service/schema.sql"));
                }
                var jdbc = new JdbcTemplate(ds);
                var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
                tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
                tx.executeWithoutResult(
                        status -> ProjectionFixture.seed(jdbc, 0, 100, true, ProjectionFixture.Distribution.UNIFORM));
                LedgerTestFixture.corrupt(jdbc, "UPDATE p_ledger_transactions SET writer_xid=NULL WHERE id<=50");
                UUID build = UUID.randomUUID();
                var catalog = new ProjectionCatalogJdbc(jdbc, ProjectionKafkaIntegrationTest.factBatch(jdbc));
                tx.executeWithoutResult(status -> {
                    catalog.create(build, "B", "test", "late commit and legacy");
                    catalog.materialize(build);
                });
                int initial = tx.execute(status -> catalog.fanout(build));
                assertThat(initial).isEqualTo(100);
                try (var late = ds.getConnection()) {
                    late.setAutoCommit(false);
                    var lateJdbc = new JdbcTemplate(new SingleConnectionDataSource(late, true));
                    ProjectionFixture.seed(lateJdbc, 95, 100, true, ProjectionFixture.Distribution.UNIFORM);
                    saveRange(lateJdbc, 95, 190);
                    tx.executeWithoutResult(status -> {
                        ProjectionFixture.seed(jdbc, 190, 100, true, ProjectionFixture.Distribution.UNIFORM);
                        saveRange(jdbc, 190, 285);
                    });
                    int newer = tx.execute(status -> catalog.fanout(build));
                    assertThat(newer).isEqualTo(100);
                    late.commit();
                }
                int older = tx.execute(status -> catalog.fanout(build));
                assertThat(older).isEqualTo(100);
                int repeated = tx.execute(status -> catalog.fanout(build));
                assertThat(repeated).isZero();
                assertThat(jdbc.queryForObject(
                                "SELECT count(*) FROM p_settlement_projection_deliveries WHERE build_id=?",
                                Long.class,
                                build))
                        .isEqualTo(300);
                var consumer = new ProjectionConsumerJdbc(jdbc, new ProjectionSinkJdbc(jdbc));
                tx.executeWithoutResult(status -> {
                    consumer.apply(build, new SettlementSourceJdbc(jdbc).facts(""));
                    catalog.claim(1000, 30000).forEach(catalog::published);
                    catalog.freeze(build);
                });
                boolean verified = tx.execute(status -> catalog.verify(build));
                assertThat(verified).isTrue();
                assertThat(jdbc.queryForObject(
                                "SELECT count(*) FROM p_settlement_projection_facts WHERE kind='SALE'", Long.class))
                        .isEqualTo(285);
                assertThat(jdbc.queryForObject(
                                "SELECT count(*) FROM p_settlement_projection_facts WHERE kind='REFUND'", Long.class))
                        .isEqualTo(15);
                assertThat(jdbc.queryForObject("SELECT sum(debit)-sum(credit) FROM p_ledger_entries", Long.class))
                        .isZero();
                assertThat(jdbc.queryForObject(
                                "SELECT count(*) FROM p_ledger_transactions WHERE action='RECEIVABLE'", Long.class))
                        .isPositive();
            }
        }
    }

    private static void saveRange(JdbcTemplate jdbc, int from, int to) {
        var source = new SettlementSourceJdbc(jdbc);
        var journal = new SettlementFactJdbc(jdbc, source);
        source.facts(" AND t.payment_id>? AND t.payment_id<=?", from, to).forEach(journal::save);
    }
}
