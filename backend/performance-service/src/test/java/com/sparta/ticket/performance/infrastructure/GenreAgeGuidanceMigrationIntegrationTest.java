package com.sparta.ticket.performance.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.performance.support.PerformanceIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** 격리된 테스트 스키마에서 구버전 데이터 보존과 이행 반복 실행을 확인한다. */
class GenreAgeGuidanceMigrationIntegrationTest extends PerformanceIntegrationTest {
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void preservesLegacyValuesAndAcceptsNewValuesAfterRepeatedMigration() throws Exception {
        jdbc.execute("CREATE SCHEMA genre_age_migration_test");
        try {
            jdbc.execute("""
                    CREATE TABLE genre_age_migration_test.p_performances (
                        id BIGINT PRIMARY KEY, title VARCHAR(200) NOT NULL,
                        genre VARCHAR(20) NOT NULL CHECK (genre IN ('CONCERT','MUSICAL','PLAY','SPORTS')),
                        age_rating VARCHAR(20) NOT NULL,
                        version BIGINT NOT NULL, updated_at TIMESTAMP NOT NULL,
                        CONSTRAINT ck_p_performances_genre CHECK (genre IN ('CONCERT','MUSICAL','PLAY','SPORTS')),
                        CONSTRAINT ck_p_performances_age_rating CHECK (age_rating IN ('ALL','12','15','19')))
                    """);
            String[] genres = {"CONCERT", "MUSICAL", "PLAY", "SPORTS"};
            String[] ratings = {"ALL", "12", "15", "19"};
            for (int i = 0; i < genres.length; i++) {
                jdbc.update(
                        "INSERT INTO genre_age_migration_test.p_performances VALUES (?,?,?,?,7,'2026-10-01')",
                        i + 1,
                        "기존 공연 " + i,
                        genres[i],
                        ratings[i]);
            }
            String originalColumns = "SELECT id,title,genre,age_rating,version,updated_at"
                    + " FROM genre_age_migration_test.p_performances ORDER BY id";
            List<Map<String, Object>> before = jdbc.queryForList(originalColumns);
            String migration = Files.readString(
                            Path.of("../sql/performance-service/migrations/262-genre-age-guidance.sql"))
                    .replace("performance_schema", "genre_age_migration_test");
            jdbc.execute(migration);
            jdbc.execute(migration);
            assertThat(jdbc.queryForList(originalColumns)).isEqualTo(before);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM genre_age_migration_test.p_performances"
                                    + " WHERE age_guidance IS NOT NULL",
                            Integer.class))
                    .isZero();
            jdbc.update("""
                    INSERT INTO genre_age_migration_test.p_performances
                    (id,title,genre,age_rating,age_guidance,version,updated_at)
                    VALUES (10,'클래식','CLASSICAL',NULL,'만 8세 이상',0,'2026-11-04')
                    """);
            assertThat(jdbc.queryForMap(
                            "SELECT age_rating,age_guidance FROM genre_age_migration_test.p_performances WHERE id=10"))
                    .containsEntry("age_rating", null)
                    .containsEntry("age_guidance", "만 8세 이상");
            for (String assignment : List.of(
                    "genre='UNKNOWN'",
                    "age_rating='18'",
                    "age_guidance=''",
                    "age_guidance='   '",
                    "age_guidance=repeat('가',101)")) {
                assertThatThrownBy(() -> jdbc.update(
                                "UPDATE genre_age_migration_test.p_performances SET " + assignment + " WHERE id=10"))
                        .isInstanceOf(DataIntegrityViolationException.class);
            }
        } finally {
            jdbc.execute("DROP TABLE IF EXISTS genre_age_migration_test.p_performances");
            jdbc.execute("DROP SCHEMA genre_age_migration_test");
        }
    }
}
