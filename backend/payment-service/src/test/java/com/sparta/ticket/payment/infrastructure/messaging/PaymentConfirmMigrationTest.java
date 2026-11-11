package com.sparta.ticket.payment.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;

class PaymentConfirmMigrationTest {
    @Test
    void ordinaryOwnerAddsMetadataWithoutChangingExistingMoneyAndRejectsPartialMetadata() throws Exception {
        String migration = Files.readString(Path.of("../sql/payment-service/migrations/025-message-approval.sql"))
                .replace("\r\n", "\n");
        String schema =
                Files.readString(Path.of("../sql/payment-service/schema.sql")).replace("\r\n", "\n");
        assertThat(schema).endsWith(migration);
        String previous = schema.substring(0, schema.length() - migration.length());
        try (var postgres = new PostgreSQLContainer<>("postgres:16")) {
            postgres.start();
            var admin = new JdbcTemplate(
                    new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
            admin.execute("CREATE ROLE confirm_app LOGIN PASSWORD 'fixture_only' NOSUPERUSER");
            admin.execute("CREATE SCHEMA payment_schema AUTHORIZATION confirm_app");
            String separator = postgres.getJdbcUrl().contains("?") ? "&" : "?";
            var ds = new DriverManagerDataSource(
                    postgres.getJdbcUrl() + separator + "currentSchema=payment_schema", "confirm_app", "fixture_only");
            try (var connection = ds.getConnection()) {
                ScriptUtils.executeSqlScript(
                        connection,
                        new EncodedResource(
                                new ByteArrayResource(previous.getBytes(StandardCharsets.UTF_8)),
                                StandardCharsets.UTF_8));
            }
            var jdbc = new JdbcTemplate(ds);
            jdbc.update("""
                INSERT INTO p_payments(order_id,reservation_id,user_id,seller_id,performance_id,schedule_id,pg_provider,
                  expires_at,total_price,discount_amount,amount,status,created_at,created_by,updated_at,updated_by)
                VALUES('legacy',1,1,1,1,1,'TOSS',LOCALTIMESTAMP+interval '1 hour',10000,1000,9000,'READY',LOCALTIMESTAMP,'TEST',LOCALTIMESTAMP,'TEST')
                """);
            var before = jdbc.queryForMap("SELECT order_id,status,total_price,discount_amount,amount FROM p_payments");
            try (var connection = ds.getConnection()) {
                connection.setAutoCommit(false);
                ScriptUtils.executeSqlScript(
                        connection,
                        new EncodedResource(
                                new ByteArrayResource(migration.getBytes(StandardCharsets.UTF_8)),
                                StandardCharsets.UTF_8));
                connection.commit();
            }
            assertThat(jdbc.queryForMap("SELECT order_id,status,total_price,discount_amount,amount FROM p_payments"))
                    .isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT rolsuper FROM pg_roles WHERE rolname=current_user", Boolean.class))
                    .isFalse();
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM p_payments WHERE message_confirm_before IS NULL AND message_confirm_fingerprint IS NULL",
                            Long.class))
                    .isEqualTo(1);
            assertThatThrownBy(() -> jdbc.update("UPDATE p_payments SET message_confirm_before=expires_at"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("UPDATE p_payments SET message_confirm_fingerprint=?", "0".repeat(64)))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM pg_indexes WHERE indexname='ix_payment_message_ready_due'",
                            Long.class))
                    .isEqualTo(1);
        }
    }
}
