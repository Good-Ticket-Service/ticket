package com.sparta.ticket.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sparta.ticket.performance.support.PerformanceIntegrationTest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;

/**
 * 로컬 시드 SQL(src/main/resources/db/seed-local.sql)을 실제 schema.sql 위에 적용한다.
 * 제약(CHECK, 부분 UNIQUE, FK)을 어기지 않고 들어가는지와 공개 조회 시나리오가 시드 주석대로 나오는지 확인한다.
 * Note: local 프로필은 Hibernate가 만든 테이블에 시드를 넣는다. 제약이 더 엄격한 schema.sql로 검사해 두면 dev DB에도 넣을 수 있다.
 */
@Sql(
        value = {"/sql/truncate.sql", "classpath:db/seed-local.sql"},
        config = @SqlConfig(encoding = "UTF-8"))
class SeedLocalSqlIntegrationTest extends PerformanceIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 시드 회차는 DB의 오늘 기준 상대 시각이라, 판매 상태를 판단하는 Clock도 실제 현재 시각으로 맞춘다. */
    @BeforeEach
    void useRealNow() {
        LocalDateTime dbNow = jdbcTemplate.queryForObject("SELECT LOCALTIMESTAMP", LocalDateTime.class);
        clock.set(dbNow);
    }

    @Test
    @DisplayName("공연장 3, 등급 7, 좌석 80, 공연 8, 회차 12개가 들어가고 다음 자동 ID가 겹치지 않는다")
    void insertsSeedRows() {
        assertThat(count("p_venues")).isEqualTo(3);
        assertThat(count("p_venue_seat_grades")).isEqualTo(7);
        assertThat(count("p_venue_seats")).isEqualTo(80);
        assertThat(count("p_performances")).isEqualTo(8);
        assertThat(count("p_performance_grade_prices")).isEqualTo(17);
        assertThat(count("p_schedules")).isEqualTo(12);
        assertThat(count("p_performance_seat_policies")).isEqualTo(1);

        Long nextVenueId = jdbcTemplate.queryForObject("""
                INSERT INTO p_venues (name, address, region, created_at, created_by, updated_at, updated_by)
                VALUES ('새 공연장', '주소', '서울', LOCALTIMESTAMP, 'SYSTEM', LOCALTIMESTAMP, 'SYSTEM')
                RETURNING id
                """, Long.class);
        assertThat(nextVenueId).isEqualTo(4L);
    }

    /** 좌석 서비스의 local 시드가 이 ID를 숫자로 쓴다(#139). 좌석 번호가 바깥, 열이 안쪽인 순서다. */
    @Test
    @DisplayName("공연장 좌석 ID는 고정값이다: 서울 1~40, 부산 41~60, 대구 61~80이고 다음 자동 ID는 81이다")
    void venueSeatIdsAreFixed() {
        assertThat(venueSeatId(1, "A", "1", 1)).isEqualTo(1L);
        assertThat(venueSeatId(1, "A", "2", 1)).isEqualTo(2L);
        assertThat(venueSeatId(1, "B", "1", 1)).isEqualTo(3L);
        assertThat(venueSeatId(1, "B", "2", 1)).isEqualTo(4L);
        assertThat(venueSeatId(1, "A", "1", 2)).isEqualTo(5L);
        assertThat(venueSeatId(1, "B", "2", 10)).isEqualTo(40L);
        assertThat(venueSeatId(2, "A", "1", 1)).isEqualTo(41L);
        assertThat(venueSeatId(2, "A", "2", 10)).isEqualTo(60L);
        assertThat(venueSeatId(3, "A", "1", 1)).isEqualTo(61L);
        assertThat(venueSeatId(3, "A", "2", 10)).isEqualTo(80L);

        Long nextSeatId = jdbcTemplate.queryForObject("""
                INSERT INTO p_venue_seats (venue_id, venue_seat_grade_id, section, row_label, seat_number,
                                           created_at, created_by, updated_at, updated_by)
                VALUES (1, 1, 'C', '1', 1, LOCALTIMESTAMP, 'SYSTEM', LOCALTIMESTAMP, 'SYSTEM')
                RETURNING id
                """, Long.class);
        assertThat(nextSeatId).isEqualTo(81L);
    }

    @Test
    @DisplayName("공개 목록에는 공연 1~5만 나오고, 예매 오픈 순 정렬은 판매 중 → 오픈 전 → 오픈할 회차 없음 순이다")
    void publicListMatchesScenario() throws Exception {
        mockMvc.perform(get("/api/performances"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].id").value(contains(4, 3, 2, 1, 5)))
                .andExpect(jsonPath("$.data.totalElements").value(5));
        mockMvc.perform(get("/api/performances").param("sort", "BOOKING_OPEN_ASC"))
                .andExpect(jsonPath("$.data.items[*].id").value(contains(1, 2, 3, 5, 4)));
    }

    @Test
    @DisplayName("상세: 판매 중 공연은 가격 3개와 기간, 회차 없는 공연은 period=null, 취소 공연은 200, 초안·삭제 공연은 404")
    void publicDetailMatchesScenario() throws Exception {
        mockMvc.perform(get("/api/performances/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("오페라의 유령"))
                .andExpect(jsonPath("$.data.venue.name").value("서울 아트센터"))
                .andExpect(jsonPath("$.data.gradePrices.length()").value(3))
                .andExpect(jsonPath("$.data.period", notNullValue()));
        mockMvc.perform(get("/api/performances/4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.period").value(nullValue()));
        mockMvc.perform(get("/api/performances/8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operationStatus").value("CANCELED"));
        mockMvc.perform(get("/api/performances/6")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/performances/7")).andExpect(status().isNotFound());
    }

    private Long venueSeatId(long venueId, String section, String rowLabel, int seatNumber) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM p_venue_seats WHERE venue_id = ? AND section = ? AND row_label = ? AND seat_number = ?",
                Long.class,
                venueId,
                section,
                rowLabel,
                seatNumber);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
