package com.sparta.ticket.payment.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.payment.support.PaymentIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RefundExecutionMigrationTest extends PaymentIntegrationTest {
    @Autowired
    private DataSource dataSource;

    @Test
    void retainsPendingRefundAndGenerationAcrossRepeatedMigration() throws Exception {
        String schema = "refund_migration_" + UUID.randomUUID().toString().replace("-", "");
        String sql = Files.readString(Path.of("../sql/payment-service/migrations/002-refund-execution-version.sql"));
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try {
                statement.execute("SET search_path TO " + schema);
                statement.execute(
                        "CREATE TABLE p_refunds (id BIGINT PRIMARY KEY, status VARCHAR(20), cancel_attempt INT, refund_amount INT)");
                statement.execute("INSERT INTO p_refunds VALUES (1,'REQUESTED',2,45000)");
                statement.execute(sql);
                statement.execute("UPDATE p_refunds SET execution_version=7 WHERE id=1");
                statement.execute(sql);
                try (var rows = statement.executeQuery("SELECT * FROM p_refunds WHERE id=1")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("status")).isEqualTo("REQUESTED");
                    assertThat(rows.getInt("cancel_attempt")).isEqualTo(2);
                    assertThat(rows.getInt("refund_amount")).isEqualTo(45000);
                    assertThat(rows.getLong("execution_version")).isEqualTo(7);
                }
            } finally {
                statement.execute("SET search_path TO public");
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
