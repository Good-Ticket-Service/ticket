package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionCatalogJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionConsumerJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionSinkJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.SettlementSourceJdbc;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

class ProjectionMigrationTest {
    @Test
    void nonSuperuserUpgradesExistingFinancialSchemaAndOwnsWorkingProjectionObjects() throws Exception {
        Path migration = Path.of("../sql/payment-service/migrations/022-settlement-projection.sql");
        String upgrade = Files.readString(migration).replace("\r\n", "\n");
        Path healthMigration = Path.of("../sql/payment-service/migrations/023-projection-preview-health.sql");
        String healthUpgrade = Files.readString(healthMigration).replace("\r\n", "\n");
        String canonical =
                Files.readString(Path.of("../sql/payment-service/schema.sql")).replace("\r\n", "\n");
        String dbUpgrade = Files.readString(Path.of("../sql/payment-service/migrations/024-projection-db-worker.sql"))
                .replace("\r\n", "\n");
        assertThat(canonical).endsWith(dbUpgrade);
        canonical = canonical.substring(0, canonical.length() - dbUpgrade.length());
        assertThat(canonical).endsWith(healthUpgrade);
        String projectionSchema = canonical.substring(0, canonical.length() - healthUpgrade.length());
        assertThat(projectionSchema).endsWith(upgrade);
        String previous = projectionSchema.substring(0, projectionSchema.length() - upgrade.length());
        try (var postgres = new PostgreSQLContainer<>("postgres:16")) {
            postgres.start();
            try (var admin = ProjectionProcessMain.dataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                var jdbc = new JdbcTemplate(admin);
                jdbc.execute(
                        "CREATE ROLE payment_app LOGIN PASSWORD 'projection_test_only' NOSUPERUSER NOCREATEDB NOCREATEROLE");
                jdbc.execute("CREATE SCHEMA payment_schema AUTHORIZATION payment_app");
                jdbc.execute("ALTER ROLE payment_app SET search_path=payment_schema");
            }
            try (var ds =
                    ProjectionProcessMain.dataSource(postgres.getJdbcUrl(), "payment_app", "projection_test_only")) {
                try (var connection = ds.getConnection()) {
                    ScriptUtils.executeSqlScript(
                            connection,
                            new EncodedResource(
                                    new ByteArrayResource(previous.getBytes(StandardCharsets.UTF_8)),
                                    StandardCharsets.UTF_8));
                }
                var jdbc = new JdbcTemplate(ds);
                var manager = new DataSourceTransactionManager(ds);
                new TransactionTemplate(manager)
                        .executeWithoutResult(status ->
                                ProjectionFixture.seed(jdbc, 0, 100, true, ProjectionFixture.Distribution.UNIFORM));
                try (var connection = ds.getConnection()) {
                    ScriptUtils.executeSqlScript(
                            connection, new EncodedResource(new FileSystemResource(migration), StandardCharsets.UTF_8));
                }
                var catalog = ProjectionKafkaIntegrationTest.proxy(
                        new ProjectionCatalogJdbc(jdbc, ProjectionKafkaIntegrationTest.factBatch(jdbc)), manager);
                UUID legacy = catalog.create(UUID.randomUUID(), "C", "migration-test", "old projection objects");
                assertThat(catalog.materialize(legacy)).isEqualTo(100);
                jdbc.update(
                        "INSERT INTO p_settlement_projection_deliveries(build_id,fact_key) "
                                + "SELECT ?,fact_key FROM p_settlement_projection_facts",
                        legacy);
                jdbc.update(
                        "INSERT INTO p_settlement_projection_applied(build_id,fact_key,meaning_hash) "
                                + "SELECT ?,fact_key,meaning_hash FROM p_settlement_projection_facts "
                                + "WHERE kind='SALE' ORDER BY payment_id LIMIT 10",
                        legacy);
                jdbc.update("""
                    INSERT INTO p_settlement_projection_totals(build_id,aggregate_key,seller_id,schedule_id,business_date,
                      sale_count,sale_amount,refund_count,refund_amount,revision,meaning_hash)
                    SELECT ?,f.seller_id||':'||f.schedule_id||':'||f.business_date,f.seller_id,f.schedule_id,f.business_date,
                      count(*),sum(f.amount),0,0,count(*),md5(f.seller_id||':'||f.schedule_id||':'||f.business_date)
                    FROM p_settlement_projection_facts f JOIN p_settlement_projection_applied a ON a.fact_key=f.fact_key
                    WHERE a.build_id=? GROUP BY f.seller_id,f.schedule_id,f.business_date
                    """, legacy, legacy);
                jdbc.update(
                        "INSERT INTO p_settlement_projection_applied(build_id,fact_key,meaning_hash) "
                                + "SELECT ?,fact_key,'wrong-legacy-hash' FROM p_settlement_projection_facts "
                                + "WHERE kind='SALE' ORDER BY payment_id OFFSET 10 LIMIT 1",
                        legacy);
                jdbc.update("UPDATE p_settlement_projection_builds SET status='FAILED' WHERE id=?", legacy);
                String payloadHash = jdbc.queryForObject(
                        "SELECT md5(string_agg(payload,'|' ORDER BY fact_key)) FROM p_settlement_projection_facts",
                        String.class);
                try (var connection = ds.getConnection()) {
                    connection.setAutoCommit(false);
                    try {
                        ScriptUtils.executeSqlScript(
                                connection,
                                new EncodedResource(new FileSystemResource(healthMigration), StandardCharsets.UTF_8));
                        ScriptUtils.executeSqlScript(
                                connection,
                                new EncodedResource(
                                        new FileSystemResource(
                                                "../sql/payment-service/migrations/024-projection-db-worker.sql"),
                                        StandardCharsets.UTF_8));
                        connection.commit();
                    } catch (Exception exception) {
                        connection.rollback();
                        throw exception;
                    }
                }
                assertThat(jdbc.queryForObject(
                                "SELECT count(*) FROM p_settlement_projection_deliveries WHERE build_id=? AND applied",
                                Long.class,
                                legacy))
                        .isEqualTo(10);
                assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM p_settlement_projection_deliveries d JOIN p_settlement_projection_facts f USING(fact_key)
                    WHERE d.seller_id<>f.seller_id OR d.schedule_id<>f.schedule_id OR d.business_date<>f.business_date
                    """, Long.class)).isZero();
                assertThat(jdbc.queryForObject(
                                "SELECT md5(string_agg(payload,'|' ORDER BY fact_key)) FROM p_settlement_projection_facts",
                                String.class))
                        .isEqualTo(payloadHash);
                assertThat(jdbc.queryForObject("SELECT current_user", String.class))
                        .isEqualTo("payment_app");
                assertThat(jdbc.queryForObject(
                                "SELECT rolsuper FROM pg_roles WHERE rolname=current_user", Boolean.class))
                        .isFalse();
                assertThat(jdbc.queryForObject(
                                "SELECT count(*) FROM pg_tables WHERE schemaname='payment_schema' "
                                        + "AND tablename LIKE 'p_settlement_projection_%' AND tableowner='payment_app'",
                                Long.class))
                        .isEqualTo(10);
                var sink = ProjectionKafkaIntegrationTest.proxy(new ProjectionSinkJdbc(jdbc), manager);
                var consumer = ProjectionKafkaIntegrationTest.proxy(new ProjectionConsumerJdbc(jdbc, sink), manager);
                UUID build =
                        catalog.create(UUID.randomUUID(), "B", "migration-test", "existing financial data upgrade");
                assertThat(catalog.materialize(build)).isEqualTo(100);
                assertThat(catalog.fanout(build)).isEqualTo(100);
                var deliveries = catalog.claim(100, 30000);
                consumer.apply(build, new SettlementSourceJdbc(jdbc).facts(""));
                catalog.publishedBatch(deliveries);
                catalog.freeze(build);
                assertThat(catalog.verify(build)).isTrue();
                assertThat(catalog.activate(build, 0)).isEqualTo(1);
                assertThat(ProjectionLoadDriver.keyDifferences(jdbc)).isZero();
                assertThat(jdbc.queryForObject("SELECT sum(debit)-sum(credit) FROM p_ledger_entries", Long.class))
                        .isZero();
                assertThatThrownBy(() -> jdbc.update("UPDATE p_settlement_projection_facts SET amount=0"))
                        .isInstanceOf(DataIntegrityViolationException.class);
            }
        }
    }
}
