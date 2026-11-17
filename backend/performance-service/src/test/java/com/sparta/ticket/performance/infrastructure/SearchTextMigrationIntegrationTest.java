package com.sparta.ticket.performance.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.performance.support.PerformanceIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 이슈 #441 수동 이행 SQL(migrations/441-search-text.sql)을 전용 테스트 스키마에 적용해 채우기·트리거·인덱스·반복 적용을 확인한다.
 * Note: 테스트 DB에는 schema.sql이 pg_trgm을 public에 설치해 두었으므로, 이행 SQL의 search_path에 public을 더한다.
 * 운영 실행(psql)은 문장을 하나씩 보내지만 JDBC는 여러 문장을 한 트랜잭션으로 묶으므로, 인덱스 문장(CONCURRENTLY)은 따로 실행한다.
 */
class SearchTextMigrationIntegrationTest extends PerformanceIntegrationTest {

    private static final String SCHEMA = "search_text_migration_441_test";
    private static final String SEP = "\u001F";
    private static final Path MIGRATION = Path.of("../sql/performance-service/migrations/441-search-text.sql");
    private static final String PART_TWO = "-- 2부: 트랜잭션 밖에서 실행한다";
    private static final Pattern CREATE_INDEX = Pattern.compile("CREATE INDEX CONCURRENTLY[^;]*;");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("삭제 행까지 채우고 version·감사 컬럼을 보존하며, 트리거·NOT NULL·trigram 인덱스를 만들고 다시 적용해도 같다")
    void fillsAllRowsAndCanBeRepeated() throws Exception {
        createTables();
        try {
            jdbc.update("INSERT INTO " + SCHEMA + ".p_venues (id, name, address) VALUES (1, '서울 공연장', '주소'),"
                    + " (2, 'Busan Hall', '주소')");
            jdbc.update(
                    "INSERT INTO " + SCHEMA + ".p_performances (id, venue_id, title, cast_info, version, updated_at,"
                            + " deleted_at) VALUES (1, 1, '피아노 독주회', 'Kim A, 이 B', 7, timestamp '2026-10-01', NULL),"
                            + " (2, 2, 'Concert', NULL, 3, timestamp '2026-10-02', timestamp '2026-10-03')");
            var auditBefore = jdbc.queryForList(
                    "SELECT id, version, updated_at, deleted_at FROM " + SCHEMA + ".p_performances ORDER BY id");

            executeMigration(true);

            assertThat(searchText(1)).isEqualTo("피아노독주회" + SEP + "kima,이b" + SEP + "서울공연장");
            assertThat(searchText(2)).isEqualTo("concert" + SEP + SEP + "busanhall");
            assertThat(jdbc.queryForList("SELECT id, version, updated_at, deleted_at FROM " + SCHEMA
                            + ".p_performances ORDER BY id"))
                    .isEqualTo(auditBefore);
            assertThat(jdbc.queryForObject(
                            "SELECT attnotnull FROM pg_attribute WHERE attrelid = '" + SCHEMA
                                    + ".p_performances'::regclass AND attname = 'search_text'",
                            Boolean.class))
                    .isTrue();
            assertThat(jdbc.queryForObject(
                            "SELECT i.indisvalid FROM pg_index i WHERE i.indexrelid = '" + SCHEMA
                                    + ".ix_p_performances_search_text'::regclass",
                            Boolean.class))
                    .isTrue();
            assertThat(jdbc.queryForObject(
                            "SELECT pg_get_indexdef('" + SCHEMA + ".ix_p_performances_search_text'::regclass)",
                            String.class))
                    .contains("USING gin", "search_text", "gin_trgm_ops");

            // 이행 뒤의 쓰기도 트리거가 맞춘다
            jdbc.update("INSERT INTO " + SCHEMA + ".p_performances (id, venue_id, title) VALUES (3, 2, 'New Show')");
            assertThat(searchText(3)).isEqualTo("newshow" + SEP + SEP + "busanhall");
            jdbc.update("UPDATE " + SCHEMA + ".p_venues SET name = '부산 홀' WHERE id = 2");
            assertThat(searchText(2)).isEqualTo("concert" + SEP + SEP + "부산홀");
            assertThat(searchText(3)).isEqualTo("newshow" + SEP + SEP + "부산홀");

            var after = jdbc.queryForList("SELECT * FROM " + SCHEMA + ".p_performances ORDER BY id");
            executeMigration(true);
            assertThat(jdbc.queryForList("SELECT * FROM " + SCHEMA + ".p_performances ORDER BY id"))
                    .isEqualTo(after);
        } finally {
            dropTables();
        }
    }

    @Test
    @DisplayName("실행 대상 DB 확인이 없으면 아무것도 바꾸지 않고 중단한다")
    void requiresExplicitDatabaseConfirmation() throws Exception {
        createTables();
        try {
            jdbc.update("INSERT INTO " + SCHEMA + ".p_venues (id, name, address) VALUES (1, '서울 공연장', '주소')");
            jdbc.update("INSERT INTO " + SCHEMA + ".p_performances (id, venue_id, title) VALUES (1, 1, '공연')");

            assertThatThrownBy(() -> executeMigration(false))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("실행 대상 DB를 명시적으로 확인");

            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM information_schema.columns WHERE table_schema = '" + SCHEMA
                                    + "' AND table_name = 'p_performances' AND column_name = 'search_text'",
                            Integer.class))
                    .isZero();
        } finally {
            dropTables();
        }
    }

    /** IF NOT EXISTS는 이름만 보므로 넘어간 뒤, 마지막 검사가 정의를 대조해 알리고 기존 인덱스는 지우지 않는다(이슈 #441 리뷰 R1-2). */
    @Test
    @DisplayName("같은 이름의 다른 정의 인덱스가 있으면 현재 정의를 알리고 중단하며 지우지 않는다")
    void stopsWhenSameNameIndexHasDifferentDefinition() throws Exception {
        createTables();
        try {
            jdbc.update("INSERT INTO " + SCHEMA + ".p_venues (id, name, address) VALUES (1, '서울 공연장', '주소')");
            jdbc.update("INSERT INTO " + SCHEMA + ".p_performances (id, venue_id, title) VALUES (1, 1, '공연')");
            jdbc.execute("CREATE INDEX ix_p_performances_search_text ON " + SCHEMA + ".p_performances (title)");

            assertThatThrownBy(() -> executeMigration(true))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("정의가 다릅니다")
                    .hasMessageContaining("(title)");

            assertThat(jdbc.queryForObject(
                            "SELECT pg_get_indexdef('" + SCHEMA + ".ix_p_performances_search_text'::regclass)",
                            String.class))
                    .contains("btree", "(title)");
        } finally {
            dropTables();
        }
    }

    private void executeMigration(boolean confirmed) throws Exception {
        String sql = Files.readString(MIGRATION)
                .replace(
                        "SET LOCAL search_path TO performance_schema;",
                        "SET LOCAL search_path TO " + SCHEMA + ", public;")
                .replace("SET search_path TO performance_schema;", "SET search_path TO " + SCHEMA + ", public;");
        int split = sql.indexOf(PART_TWO);
        assertThat(split).isPositive();
        String partOne = sql.substring(0, split);
        String partTwo = sql.substring(split);
        Matcher createIndex = CREATE_INDEX.matcher(partTwo);
        assertThat(createIndex.find()).isTrue();

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("SELECT set_config('performance.search_text_migration_database', "
                    + (confirmed ? "current_database()" : "''") + ", false)");
            try {
                statement.execute(partOne);
                statement.execute("SET search_path TO " + SCHEMA + ", public");
                statement.execute(createIndex.group());
                statement.execute(partTwo.substring(createIndex.end()));
            } catch (SQLException exception) {
                statement.execute("ROLLBACK");
                throw exception;
            } finally {
                statement.execute("RESET performance.search_text_migration_database");
                statement.execute("RESET search_path");
            }
        }
    }

    private String searchText(long performanceId) {
        return jdbc.queryForObject(
                "SELECT search_text FROM " + SCHEMA + ".p_performances WHERE id = ?", String.class, performanceId);
    }

    private void createTables() {
        jdbc.execute("CREATE SCHEMA " + SCHEMA);
        jdbc.execute("CREATE TABLE " + SCHEMA + ".p_venues (id bigint PRIMARY KEY, name varchar(100) NOT NULL,"
                + " address varchar(255))");
        jdbc.execute("CREATE TABLE " + SCHEMA + ".p_performances (id bigint PRIMARY KEY,"
                + " venue_id bigint NOT NULL REFERENCES " + SCHEMA + ".p_venues (id), title varchar(200) NOT NULL,"
                + " cast_info varchar(500), version bigint, updated_at timestamp, deleted_at timestamp)");
    }

    private void dropTables() {
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }
}
