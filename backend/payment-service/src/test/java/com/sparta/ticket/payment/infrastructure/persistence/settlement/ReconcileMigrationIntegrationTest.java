package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

class ReconcileMigrationIntegrationTest {
    @Test
    void legacyNullWritersAreIncludedByInitialFullAndNewWritersAreStamped() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16")) {
            postgres.start();
            var ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            String schema = Files.readString(Path.of("../sql/payment-service/schema.sql"));
            String before = schema.substring(0, schema.indexOf("-- 신규 원장 메타데이터"));
            new ResourceDatabasePopulator(new ByteArrayResource(before.getBytes(StandardCharsets.UTF_8))).execute(ds);
            var jdbc = new JdbcTemplate(ds);
            var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
            tx.executeWithoutResult(status -> ReconcilePerformanceTest.seed(jdbc, 2));
            new ResourceDatabasePopulator(
                            new FileSystemResource("../sql/payment-service/migrations/019-reconcile-incremental.sql"))
                    .execute(ds);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM p_ledger_transactions WHERE writer_xid IS NULL", Long.class))
                    .isEqualTo(2);
            var store = new ReconcileBatchJdbcStore(
                    jdbc, new SettlementReconcileJdbcAdapter(jdbc, Duration.ofSeconds(10)), "");
            tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            assertThat(tx.execute(status -> store.database(1, "DAILY")).valid()).isTrue();
            tx.executeWithoutResult(status -> ReconcilePerformanceTest.seedRange(jdbc, 2, 1));
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM p_ledger_transactions WHERE writer_xid IS NOT NULL", Long.class))
                    .isEqualTo(1);
            var daily = tx.execute(status -> store.database(2, "DAILY"));
            assertThat(daily.valid()).isTrue();
            assertThat(jdbc.queryForObject(
                            "SELECT delta_headers FROM p_settlement_reconcile_runs WHERE id=?",
                            Long.class,
                            daily.runId()))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT sum(credit-debit) FROM p_reconcile_account_balances", Long.class))
                    .isEqualTo(3 * 8680);
        }
    }
}
