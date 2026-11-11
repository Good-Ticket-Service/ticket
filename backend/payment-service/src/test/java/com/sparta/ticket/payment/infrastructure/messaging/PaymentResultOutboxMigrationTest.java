package com.sparta.ticket.payment.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

class PaymentResultOutboxMigrationTest {
    @Test
    void regularApplicationOwnerCanMigrateWithoutRewritingExistingPayments() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16")) {
            postgres.start();
            var admin = new JdbcTemplate(
                    new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
            admin.execute("CREATE ROLE result_app LOGIN PASSWORD 'fixture_only'");
            admin.execute("CREATE SCHEMA payment_schema AUTHORIZATION result_app");
            String separator = postgres.getJdbcUrl().contains("?") ? "&" : "?";
            var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                    postgres.getJdbcUrl() + separator + "currentSchema=payment_schema", "result_app", "fixture_only"));
            jdbc.execute("CREATE TABLE p_payments(id BIGINT PRIMARY KEY,status TEXT)");
            jdbc.update("INSERT INTO p_payments VALUES(1,'DONE'),(2,'FAILED')");
            jdbc.execute(Files.readString(Path.of("../sql/payment-service/migrations/021-payment-result-outbox.sql")));
            assertThat(jdbc.queryForObject("SELECT rolsuper FROM pg_roles WHERE rolname=current_user", Boolean.class))
                    .isFalse();
            assertThat(jdbc.queryForList("SELECT status FROM p_payments ORDER BY id", String.class))
                    .containsExactly("DONE", "FAILED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM p_payment_result_outbox", Long.class))
                    .isZero();
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM pg_indexes WHERE schemaname='payment_schema' "
                                    + "AND indexname='ix_payment_result_outbox_pending'",
                            Long.class))
                    .isEqualTo(1);
        }
    }
}
