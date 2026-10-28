package com.sparta.ticket.payment.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.payment.support.PaymentIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RefundRecoveryPolicyMigrationTest extends PaymentIntegrationTest {
    @Autowired
    private DataSource dataSource;

    @Test
    void onlyKnownDeadlineBlocksResumeAndMigrationKeepsNewProgress() throws Exception {
        String schema = "refund_policy_" + UUID.randomUUID().toString().replace("-", "");
        String sql = Files.readString(Path.of("../sql/payment-service/migrations/003-refund-recovery-policy.sql"));
        try (var connection = dataSource.getConnection();
                var st = connection.createStatement()) {
            st.execute("CREATE SCHEMA " + schema);
            try {
                st.execute("SET search_path TO " + schema);
                st.execute(
                        "CREATE TABLE p_refunds(id BIGINT PRIMARY KEY,status VARCHAR(20),recovery_blocked BOOLEAN,"
                                + "last_error VARCHAR(80),requested_at TIMESTAMP,recovery_started_at TIMESTAMP,next_attempt_at TIMESTAMP)");
                st.execute("INSERT INTO p_refunds VALUES "
                        + "(1,'REQUESTED',true,'REFUND_RECOVERY_DEADLINE','2026-10-01','2026-10-02',NULL),"
                        + "(2,'REQUESTED',true,'MANUAL_PAUSE','2026-10-01','2026-10-02',NULL),"
                        + "(3,'FAILED',true,'BUSINESS_DENIED','2026-10-01','2026-10-02',NULL),"
                        + "(4,'REQUESTED',false,NULL,NULL,NULL,'2026-10-03'),"
                        + "(5,'REQUESTED',false,NULL,'2026-10-01','2026-10-02','2026-10-03'),"
                        + "(6,'REQUESTED',true,'UNKNOWN_REASON','2026-10-01','2026-10-02',NULL)");
                st.execute(sql);
                try (var rows = st.executeQuery("SELECT * FROM p_refunds ORDER BY id")) {
                    rows.next();
                    assertThat(rows.getString("recovery_mode")).isEqualTo("SLOW");
                    assertThat(rows.getBoolean("recovery_blocked")).isFalse();
                    assertThat(rows.getTimestamp("next_attempt_at")).isNotNull();
                    assertThat(rows.getTimestamp("cancel_key_created_at").toLocalDateTime())
                            .isEqualTo("2026-10-01T00:00:00");
                    rows.next();
                    assertThat(rows.getString("recovery_mode")).isEqualTo("PAUSED");
                    assertThat(rows.getBoolean("recovery_blocked")).isTrue();
                    rows.next();
                    assertThat(rows.getString("recovery_mode")).isEqualTo("PAUSED");
                    rows.next();
                    assertThat(rows.getString("recovery_mode")).isEqualTo("VERIFY_ONLY");
                    rows.next();
                    assertThat(rows.getString("recovery_mode")).isEqualTo("ACTIVE");
                    rows.next();
                    assertThat(rows.getString("recovery_mode")).isEqualTo("PAUSED");
                }
                st.execute(
                        "UPDATE p_refunds SET recovery_mode='VERIFY_ONLY',slow_attempts=7,next_attempt_at='2026-11-01' WHERE id=1");
                st.execute(sql);
                try (var rows = st.executeQuery(
                        "SELECT recovery_mode,slow_attempts,next_attempt_at FROM p_refunds WHERE id=1")) {
                    rows.next();
                    assertThat(rows.getString(1)).isEqualTo("VERIFY_ONLY");
                    assertThat(rows.getInt(2)).isEqualTo(7);
                    assertThat(rows.getTimestamp(3).toLocalDateTime()).isEqualTo("2026-11-01T00:00:00");
                }
            } finally {
                st.execute("SET search_path TO public");
                st.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
