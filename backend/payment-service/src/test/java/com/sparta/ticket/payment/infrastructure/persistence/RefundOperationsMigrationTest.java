package com.sparta.ticket.payment.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.payment.support.PaymentIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RefundOperationsMigrationTest extends PaymentIntegrationTest {
    @Autowired
    private DataSource dataSource;

    @Test
    void repeatedMigrationPreservesUnknownRestrictionTimeAndCommand() throws Exception {
        String schema = "ops_migration_" + UUID.randomUUID().toString().replace("-", "");
        String sql = Files.readString(Path.of("../sql/payment-service/migrations/004-refund-operations.sql"));
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try {
                statement.execute("SET search_path TO " + schema);
                statement.execute(
                        "CREATE TABLE p_refunds (id BIGINT PRIMARY KEY, refund_amount INT, cancel_attempt INT)");
                statement.execute("INSERT INTO p_refunds VALUES (1,10000,2)");
                statement.execute(sql);
                statement.execute("""
                        INSERT INTO p_refund_operation_commands
                        (command_id,input_hash,payment_id,refund_id,action,actor,reason,result,explanation,
                         expected_state,before_state,after_state,inquiry,processed_at,created_at,created_by,updated_at,updated_by)
                        VALUES ('cmd-test','hash',1,1,'RESUME','operator','RESUME_OBSERVATION','APPLIED','TEST',
                        'FAILED:PAUSED:1:0','FAILED:PAUSED:1:0','FAILED:VERIFY_ONLY:2:0','UNKNOWN',
                        now(),now(),'test',now(),'test')
                        """);
                statement.execute(sql);
                try (var row = statement.executeQuery("SELECT * FROM p_refunds WHERE id=1")) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getObject("restriction_started_at")).isNull();
                    assertThat(row.getInt("refund_amount")).isEqualTo(10000);
                    assertThat(row.getInt("cancel_attempt")).isEqualTo(2);
                }
                try (var row = statement.executeQuery("SELECT count(*) FROM p_refund_operation_commands")) {
                    row.next();
                    assertThat(row.getInt(1)).isEqualTo(1);
                }
            } finally {
                statement.execute("SET search_path TO public");
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
