package com.sparta.ticket.payment.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.payment.support.PaymentIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PaymentReconcileMigrationTest extends PaymentIntegrationTest {
    @Autowired
    private DataSource dataSource;

    @Test
    void migratesLegacyRowsWithoutSkippingNewStagesAndCanResumeSafely() throws Exception {
        String schema = "migration_" + UUID.randomUUID().toString().replace("-", "");
        String migration = Files.readString(Path.of("../sql/payment-service/migrations/001-reconcile-checkpoints.sql"));
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try {
                statement.execute("SET search_path TO " + schema);
                statement.execute("CREATE TABLE p_payments (id BIGINT PRIMARY KEY, status VARCHAR(20),"
                        + " approve_requested_at TIMESTAMP, updated_at TIMESTAMP, reconciled_at TIMESTAMP)");
                statement.execute("INSERT INTO p_payments VALUES"
                        + " (1,'FAILED','2026-10-01 00:00','2026-10-03 00:00',NULL),"
                        + " (2,'FAILED','2026-10-01 00:00','2026-10-03 00:00','2026-10-01 00:11'),"
                        + " (3,'FAILED',NULL,'2026-10-03 00:00',NULL),"
                        + " (4,'DONE','2026-10-01 00:00','2026-10-03 00:00',NULL),"
                        + " (5,'FAILED','2026-10-01 00:00','2026-10-03 00:00','2026-10-01 00:01')");
                statement.execute(migration);
                try (var rows = statement.executeQuery("SELECT * FROM p_payments ORDER BY id")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt("reconcile_count")).isZero();
                    assertThat(rows.getTimestamp("next_reconcile_at").toLocalDateTime())
                            .isEqualTo("2026-10-01T00:01:00");
                    assertThat(rows.getTimestamp("failed_at").toLocalDateTime()).isEqualTo("2026-10-01T00:00:00");
                    rows.next();
                    assertThat(rows.getInt("reconcile_count")).isZero();
                    assertThat(rows.getTimestamp("next_reconcile_at").toLocalDateTime())
                            .isEqualTo("2026-10-01T00:01:00");
                    rows.next();
                    assertThat(rows.getTimestamp("next_reconcile_at")).isNull();
                    rows.next();
                    assertThat(rows.getTimestamp("failed_at")).isNull();
                    rows.next();
                    assertThat(rows.getInt("reconcile_count")).isZero();
                }
                // 새 실행자가 다섯 번 확인을 마친 뒤 이행 스크립트를 재실행해도 다시 열지 않는다.
                statement.execute("UPDATE p_payments SET reconcile_count=5,next_reconcile_at=NULL WHERE id=1");
                statement.execute(migration);
                try (var rows =
                        statement.executeQuery("SELECT reconcile_count,next_reconcile_at FROM p_payments WHERE id=1")) {
                    rows.next();
                    assertThat(rows.getInt(1)).isEqualTo(5);
                    assertThat(rows.getTimestamp(2)).isNull();
                }
            } finally {
                statement.execute("SET search_path TO public");
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    @Test
    void mapsOldTwoStepProgressWithoutReinterpretingCountAsNewStage() throws Exception {
        String schema = "migration_" + UUID.randomUUID().toString().replace("-", "");
        String migration = Files.readString(Path.of("../sql/payment-service/migrations/001-reconcile-checkpoints.sql"));
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try {
                statement.execute("SET search_path TO " + schema);
                statement.execute("CREATE TABLE p_payments (id BIGINT PRIMARY KEY,status VARCHAR(20),"
                        + " approve_requested_at TIMESTAMP,updated_at TIMESTAMP,failed_at TIMESTAMP,"
                        + " reconcile_count INT NOT NULL DEFAULT 0,next_reconcile_at TIMESTAMP,"
                        + " CONSTRAINT ck_p_payments_reconcile_count CHECK (reconcile_count BETWEEN 0 AND 2))");
                statement.execute("INSERT INTO p_payments VALUES"
                        + " (1,'FAILED','2026-10-01 00:00','2026-10-03 00:00','2026-10-01 00:02',1,'2026-10-02 00:02'),"
                        + " (2,'FAILED','2026-10-01 00:00','2026-10-03 00:00','2026-10-01 00:02',2,NULL)");
                statement.execute(migration);
                try (var rows = statement.executeQuery("SELECT * FROM p_payments ORDER BY id")) {
                    rows.next();
                    assertThat(rows.getInt("reconcile_count")).isZero();
                    assertThat(rows.getString("reconcile_checkpoints_minutes")).isEqualTo("1,5,15,60,1440");
                    assertThat(rows.getTimestamp("failed_at").toLocalDateTime()).isEqualTo("2026-10-01T00:02:00");
                    assertThat(rows.getTimestamp("next_reconcile_at").toLocalDateTime())
                            .isEqualTo("2026-10-01T00:03:00");
                    assertThat(rows.getInt("reconcile_retry_count")).isZero();
                    assertThat(rows.getTimestamp("reconcile_unknown_since")).isNull();
                    rows.next();
                    assertThat(rows.getInt("reconcile_count")).isEqualTo(5);
                    assertThat(rows.getTimestamp("next_reconcile_at")).isNull();
                }
                statement.execute(
                        "UPDATE p_payments SET reconcile_count=1,reconcile_retry_count=4,"
                                + " reconcile_unknown_since='2026-10-01 00:07',next_reconcile_at='2026-10-01 00:08' WHERE id=1");
                statement.execute(migration);
                try (var rows = statement.executeQuery("SELECT * FROM p_payments WHERE id=1")) {
                    rows.next();
                    assertThat(rows.getInt("reconcile_count")).isEqualTo(1);
                    assertThat(rows.getInt("reconcile_retry_count")).isEqualTo(4);
                    assertThat(rows.getTimestamp("next_reconcile_at").toLocalDateTime())
                            .isEqualTo("2026-10-01T00:08:00");
                }
            } finally {
                statement.execute("SET search_path TO public");
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
