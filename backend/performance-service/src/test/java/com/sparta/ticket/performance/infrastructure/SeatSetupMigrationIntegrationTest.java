package com.sparta.ticket.performance.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.performance.support.PerformanceIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 기존 실패 데이터의 보존과 반복 가능한 마이그레이션을 검증한다. */
class SeatSetupMigrationIntegrationTest extends PerformanceIntegrationTest {
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void migratesExistingFailureWithoutGuessingItsCauseAndCanBeAppliedTwice() throws Exception {
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS seat_setup_migration_test");
        jdbc.execute("CREATE TABLE seat_setup_migration_test.p_schedules (id BIGINT PRIMARY KEY,"
                + "seat_setup_status VARCHAR(20),deleted_at TIMESTAMP,seat_setup_request_id VARCHAR(36))");
        try {
            jdbc.update(
                    "INSERT INTO seat_setup_migration_test.p_schedules VALUES (1,'FAILED',NULL,'original-request')");
            String migration = Files.readString(
                            Path.of("../sql/performance-service/migrations/252-seat-setup-recovery.sql"))
                    .replace("performance_schema", "seat_setup_migration_test");
            jdbc.execute(migration);
            jdbc.execute(migration);
            assertThat(jdbc.queryForMap("SELECT * FROM seat_setup_migration_test.p_schedules WHERE id=1"))
                    .containsEntry("seat_setup_status", "FAILED")
                    .containsEntry("seat_setup_request_id", "original-request")
                    .containsEntry("seat_setup_failure_kind", null)
                    .containsEntry("seat_setup_recovery_attempts", 0)
                    .containsEntry("seat_setup_recovery_limit", 0)
                    .containsEntry("seat_setup_recovery_next_at", null);
        } finally {
            jdbc.execute("DROP TABLE seat_setup_migration_test.p_schedules");
        }
    }
}
