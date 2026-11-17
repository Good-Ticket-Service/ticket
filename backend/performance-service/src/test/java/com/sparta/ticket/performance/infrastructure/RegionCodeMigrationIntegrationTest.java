package com.sparta.ticket.performance.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.performance.support.PerformanceIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 전용 테스트 스키마에서 실제 수동 이행 SQL의 원자성·삭제 행·반복 적용을 확인한다. */
class RegionCodeMigrationIntegrationTest extends PerformanceIntegrationTest {

    private static final String SCHEMA = "region_migration_438_test";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("정식명·짧은 이름·옛 이름·코드를 변환하고 삭제·ID·감사·version을 보존한다")
    void migratesAllRowsAndCanBeRepeated() throws Exception {
        createTable();
        try {
            String[] legacy = {"서울특별시", "부산", "대구", "강원도", "전라북도", "광주광역시", "전라남도", "제주도", "SEOUL"};
            for (int i = 0; i < legacy.length; i++) {
                jdbc.update(
                        "INSERT INTO " + SCHEMA
                                + ".p_venues VALUES (?, ?, timestamp '2026-11-01', 7, timestamp '2026-10-01', 'ORIGINAL')",
                        i + 1,
                        legacy[i]);
            }
            var identityBefore = jdbc.queryForList(
                    "SELECT id, deleted_at, version, updated_at, updated_by FROM " + SCHEMA + ".p_venues ORDER BY id");
            executeMigration();
            assertThat(jdbc.queryForList("SELECT region FROM " + SCHEMA + ".p_venues ORDER BY id", String.class))
                    .containsExactly(
                            "SEOUL",
                            "BUSAN",
                            "DAEGU",
                            "GANGWON",
                            "JEONBUK",
                            "JEONNAM_GWANGJU",
                            "JEONNAM_GWANGJU",
                            "JEJU",
                            "SEOUL");
            var after = jdbc.queryForList("SELECT * FROM " + SCHEMA + ".p_venues ORDER BY id");
            assertThat(jdbc.queryForList("SELECT id, deleted_at, version, updated_at, updated_by FROM " + SCHEMA
                            + ".p_venues ORDER BY id"))
                    .isEqualTo(identityBefore);
            executeMigration();
            assertThat(jdbc.queryForList("SELECT * FROM " + SCHEMA + ".p_venues ORDER BY id"))
                    .isEqualTo(after);
            assertThatThrownBy(() -> jdbc.update("INSERT INTO " + SCHEMA + ".p_venues (id, region) VALUES (100, '서울')"))
                    .hasMessageContaining("ck_p_venues_region");
        } finally {
            dropTable();
        }
    }

    @Test
    @DisplayName("미대응 값이 삭제 행에 있어도 값을 알리고 전체 이행을 롤백한다")
    void rollsBackWhenDeletedRowHasUnknownRegion() throws Exception {
        createTable();
        try {
            jdbc.update("INSERT INTO " + SCHEMA + ".p_venues (id, region) VALUES (1, '서울')");
            jdbc.update("INSERT INTO " + SCHEMA + ".p_venues (id, region, deleted_at) VALUES (2, '미대응', now())");
            assertThatThrownBy(this::executeMigration)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("미대응");
            assertThat(jdbc.queryForList("SELECT region FROM " + SCHEMA + ".p_venues ORDER BY id", String.class))
                    .isEqualTo(List.of("서울", "미대응"));
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM pg_constraint WHERE conname = 'ck_p_venues_region'"
                                    + " AND conrelid = '" + SCHEMA + ".p_venues'::regclass",
                            Integer.class))
                    .isZero();
        } finally {
            dropTable();
        }
    }

    private void executeMigration() throws Exception {
        executeMigration(true);
    }

    @Test
    @DisplayName("실행 대상 DB 확인이 없으면 변경하지 않고 중단하며 확인 뒤에만 이행한다")
    void requiresExplicitDatabaseConfirmation() throws Exception {
        createTable();
        try {
            jdbc.update("INSERT INTO " + SCHEMA + ".p_venues (id, region) VALUES (1, '서울')");
            assertThatThrownBy(() -> executeMigration(false))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("실행 대상 DB를 명시적으로 확인");
            assertThat(jdbc.queryForObject("SELECT region FROM " + SCHEMA + ".p_venues WHERE id = 1", String.class))
                    .isEqualTo("서울");
            executeMigration();
            assertThat(jdbc.queryForObject("SELECT region FROM " + SCHEMA + ".p_venues WHERE id = 1", String.class))
                    .isEqualTo("SEOUL");
        } finally {
            dropTable();
        }
    }

    private void executeMigration(boolean confirmed) throws Exception {
        String sql = Files.readString(Path.of("../sql/performance-service/migrations/438-region-codes.sql"))
                .replace("SET LOCAL search_path TO performance_schema;", "SET LOCAL search_path TO " + SCHEMA + ";");
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("SELECT set_config('performance.region_migration_database', "
                    + (confirmed ? "current_database()" : "''") + ", false)");
            try {
                statement.execute(sql);
            } catch (SQLException exception) {
                statement.execute("ROLLBACK");
                throw exception;
            } finally {
                statement.execute("RESET performance.region_migration_database");
            }
        }
    }

    private void createTable() {
        jdbc.execute("CREATE SCHEMA " + SCHEMA);
        jdbc.execute("CREATE TABLE " + SCHEMA + ".p_venues (id bigint PRIMARY KEY, region varchar(20) NOT NULL,"
                + " deleted_at timestamp, version bigint, updated_at timestamp, updated_by varchar(100))");
    }

    private void dropTable() {
        jdbc.execute("DROP TABLE " + SCHEMA + ".p_venues");
        jdbc.execute("DROP SCHEMA " + SCHEMA);
    }
}
