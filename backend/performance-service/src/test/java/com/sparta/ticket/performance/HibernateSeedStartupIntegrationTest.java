package com.sparta.ticket.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sparta.ticket.performance.domain.venue.VenueSeat;
import com.sparta.ticket.performance.infrastructure.persistence.VenueSeatJpaRepository;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/** 새 PostgreSQL에 Hibernate로 테이블을 만들고 local 설정에 등록된 시드로 기동한다. */
@SpringBootTest(
        properties = {
            "spring.cloud.config.enabled=false",
            "eureka.client.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create",
            "spring.config.import=file:../config-repo/performance-service-local.yml",
            "performance.seat-setup.retry.enabled=false",
            "performance.seat-setup.recovery.enabled=false"
        })
@AutoConfigureMockMvc
class HibernateSeedStartupIntegrationTest {
    // Note: schema.sql 검증용 공통 DB와 분리해 다른 테스트의 테이블을 다시 만들지 않는다.
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static {
        POSTGRES.start();
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private VenueSeatJpaRepository seats;

    @Test
    void savesJpaSeatsAfterHibernateDdlAndAllLocalSeeds() {
        long max = jdbc.queryForObject("SELECT max(id) FROM p_venue_seats", Long.class);
        var saved = seats.saveAllAndFlush(IntStream.rangeClosed(1, 123)
                .mapToObj(i -> VenueSeat.create(900L, 900L, "JPA", "1", i))
                .toList());
        assertThat(saved).hasSize(123);
        assertThat(saved).extracting(VenueSeat::getId).doesNotHaveDuplicates().allMatch(id -> id > max);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM p_venue_seats WHERE section='JPA'"
                                + " AND created_by='SYSTEM' AND updated_by='SYSTEM'"
                                + " AND created_at IS NOT NULL AND updated_at IS NOT NULL",
                        Integer.class))
                .isEqualTo(123);
    }

    @Test
    void startsWithLocalSeedsAndInitializesRecoveryCounters() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM p_schedules", Integer.class))
                .isEqualTo(8640);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM p_schedules WHERE seat_setup_recovery_attempts=0"
                                + " AND seat_setup_recovery_limit=0 AND seat_setup_failure_kind IS NULL"
                                + " AND seat_setup_recovery_next_at IS NULL",
                        Integer.class))
                .isEqualTo(8640);
    }

    @Test
    void seedsPublishedDescriptionsAndTestPosterUrls() {
        String scope = " FROM p_performances WHERE created_by='KOPIS_SEED' AND publication_status='PUBLISHED'";
        assertThat(jdbc.queryForObject(
                        "SELECT count(DISTINCT poster_url)" + scope
                                + " AND poster_url ~ '^https://example[.]com/posters/kopis/PF[0-9]+[.]jpg$'",
                        Integer.class))
                .isEqualTo(1116);
        assertThat(jdbc.queryForObject("SELECT count(*)" + scope + " AND description='작품 소개는 준비 중입니다.'", Integer.class))
                .isEqualTo(982);
        // 원본 XML에서 PF ID 순으로 집계한 지문. 줄바꿈을 포함해 134개 원문과 그 순서를 대조한다.
        assertThat(jdbc.queryForObject(
                        "SELECT md5(string_agg(md5(description), '' ORDER BY poster_url COLLATE \"C\"))" + scope
                                + " AND description <> '작품 소개는 준비 중입니다.'",
                        String.class))
                .isEqualTo("63e2cf886d7b75058e151ee20b086887");
    }

    @Test
    void loadsAllKopisShowsAndSharesPhysicalSeatsAcrossShowsInTheSameHall() {
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM p_performances WHERE created_by='KOPIS_SEED'", Integer.class))
                .isEqualTo(1116);
        assertThat(jdbc.queryForObject(
                        "SELECT count(DISTINCT venue_id) FROM p_performances WHERE created_by='KOPIS_SEED'",
                        Integer.class))
                .isEqualTo(552);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM p_venue_seats WHERE created_by='KOPIS_SEED'", Integer.class))
                .isEqualTo(244849);
        assertThat(jdbc.queryForObject(
                        "SELECT count(DISTINCT p.id) FROM p_performances p JOIN p_schedules s ON s.performance_id=p.id"
                                + " JOIN p_performance_grade_prices g ON g.performance_id=p.id"
                                + " JOIN p_venue_seat_grades v ON v.id=g.venue_seat_grade_id AND v.venue_id=p.venue_id"
                                + " WHERE p.created_by='KOPIS_SEED'",
                        Integer.class))
                .isEqualTo(1116);
    }

    @Test
    void findsRecitalByClassicalGenreAndReturnsItsAgeGuidance() throws Exception {
        String title = "판타지아 영아티스트 콘서트 시리즈, 김채윤 피아노 독주회 [전남광주]";
        Long id = jdbc.queryForObject("SELECT id FROM p_performances WHERE title=?", Long.class, title);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM p_venue_seats s JOIN p_performances p ON s.venue_id=p.venue_id"
                                + " WHERE p.id=?",
                        Integer.class,
                        id))
                .isEqualTo(150);
        // Note: 고정 날짜의 시드 내용을 확인하므로 실제 실행일에 종료됐어도 조회하도록 세 상태를 선택한다.
        mvc.perform(get("/api/performances")
                        .param("status", "ON_SALE,UPCOMING,ENDED")
                        .param("genre", "CLASSICAL")
                        .param("keyword", "김채윤"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].title").value(title));
        mvc.perform(get("/api/performances")
                        .param("status", "ON_SALE,UPCOMING,ENDED")
                        .param("genre", "CONCERT")
                        .param("keyword", "김채윤"))
                .andExpect(jsonPath("$.data.totalElements").value(0));
        mvc.perform(get("/api/performances/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.genre").value("CLASSICAL"))
                .andExpect(jsonPath("$.data.ageGuidance").value("만 7세 이상"))
                .andExpect(jsonPath("$.data.ageRating").isEmpty())
                .andExpect(jsonPath("$.data.gradePrices[0].price").value(10000));
        mvc.perform(get("/api/performances/{id}/schedules", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.schedules.length()").value(1))
                .andExpect(jsonPath("$.data.schedules[0].startAt").value("2026-11-03T19:30:00+09:00"));
    }

    @Test
    void loadsKopisPerformanceWithSeatsPriceAndWeekendSchedules() {
        Long id = jdbc.queryForObject("SELECT id FROM p_performances WHERE title = '잭과 콩나무 [전남 광주]'", Long.class);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM p_venue_seats s JOIN p_performances p ON s.venue_id=p.venue_id"
                                + " JOIN p_performance_grade_prices g ON g.performance_id=p.id"
                                + " AND g.venue_seat_grade_id=s.venue_seat_grade_id WHERE p.id=? AND g.price=25000",
                        Integer.class,
                        id))
                .isEqualTo(50);
        assertThat(jdbc.queryForObject(
                        "SELECT count(DISTINCT start_at) FROM p_schedules WHERE performance_id=?"
                                + " AND start_at::date BETWEEN '2026-11-03' AND '2026-11-29'"
                                + " AND extract(isodow FROM start_at) IN (6,7)"
                                + " AND start_at::time IN ('12:00','14:00','16:00') AND seat_setup_status='PENDING'",
                        Integer.class,
                        id))
                .isEqualTo(24);
    }
}
