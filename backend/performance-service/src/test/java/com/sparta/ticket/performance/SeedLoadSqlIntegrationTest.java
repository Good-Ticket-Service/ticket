package com.sparta.ticket.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sparta.ticket.performance.support.PerformanceIntegrationTest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 부하 측정용 시드(src/main/resources/db/seed-load.sql, #173)를 local 기동과 같은 순서(seed-local.sql 다음)로 실제 schema.sql 위에
 * 넣는다. 제약(CHECK, 부분 UNIQUE, FK)을 어기지 않는지, 배치·가격·판매 제외가 시드 주석대로인지, 회차 900이 판매 검증을 통과하는지 본다.
 * 좌석 서비스 시드(reservation-service db/seed-load.sql)가 같은 공연장 좌석 ID 공식·등급·가격·판매 제외를 쓴다. 바꾸면 둘을 함께 고친다.
 */
@Sql(
        value = {"/sql/truncate.sql", "classpath:db/seed-local.sql", "classpath:db/seed-load.sql"},
        config = @SqlConfig(encoding = "UTF-8"))
class SeedLoadSqlIntegrationTest extends PerformanceIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 시드 회차는 DB의 오늘 기준 상대 시각이라, 판매 상태를 판단하는 Clock도 실제 현재 시각으로 맞춘다. */
    @BeforeEach
    void useRealNow() {
        LocalDateTime dbNow = jdbcTemplate.queryForObject("SELECT LOCALTIMESTAMP", LocalDateTime.class);
        clock.set(dbNow);
    }

    @Test
    @DisplayName("공연장 900에 좌석 1,000석이 들어가고 ID는 100001~101000이다. 공식: 100000 + 구역 순번 × 250 + (열 - 1) × 25 + 좌석 번호")
    void insertsThousandVenueSeats() {
        Map<String, Object> seats = jdbcTemplate.queryForMap(
                "SELECT count(*) AS cnt, min(id) AS min_id, max(id) AS max_id FROM p_venue_seats WHERE venue_id = 900");
        assertThat(((Number) seats.get("cnt")).intValue()).isEqualTo(1000);
        assertThat(((Number) seats.get("min_id")).longValue()).isEqualTo(100001L);
        assertThat(((Number) seats.get("max_id")).longValue()).isEqualTo(101000L);

        assertThat(venueSeatId("A", "1", 1)).isEqualTo(100001L);
        assertThat(venueSeatId("A", "2", 1)).isEqualTo(100026L);
        assertThat(venueSeatId("B", "1", 1)).isEqualTo(100251L);
        assertThat(venueSeatId("D", "10", 25)).isEqualTo(101000L);
    }

    @Test
    @DisplayName("등급은 열 단위다: 1~3열 VIP 300석, 4~7열 R 400석, 8~10열 S 300석이고 가격은 150000·120000·90000이다")
    void gradesAndPrices() {
        List<Map<String, Object>> grades = jdbcTemplate.queryForList("""
                SELECT g.name, count(*) AS seats, min(s.row_label::int) AS min_row, max(s.row_label::int) AS max_row,
                       p.price
                  FROM p_venue_seats s
                  JOIN p_venue_seat_grades g ON g.id = s.venue_seat_grade_id
                  JOIN p_performance_grade_prices p ON p.venue_seat_grade_id = g.id AND p.performance_id = 900
                 WHERE s.venue_id = 900
                 GROUP BY g.name, p.price
                 ORDER BY p.price DESC
                """);

        assertThat(grades).extracting(row -> row.get("name")).containsExactly("VIP", "R", "S");
        assertThat(grades)
                .extracting(row -> ((Number) row.get("seats")).intValue())
                .containsExactly(300, 400, 300);
        assertThat(grades)
                .extracting(row -> ((Number) row.get("min_row")).intValue())
                .containsExactly(1, 4, 8);
        assertThat(grades)
                .extracting(row -> ((Number) row.get("max_row")).intValue())
                .containsExactly(3, 7, 10);
        assertThat(grades)
                .extracting(row -> ((Number) row.get("price")).intValue())
                .containsExactly(150_000, 120_000, 90_000);
    }

    @Test
    @DisplayName("판매 제외는 구역마다 1열 1번, 4석이다")
    void excludedSeats() {
        List<Long> excluded = jdbcTemplate.queryForList(
                "SELECT venue_seat_id FROM p_performance_seat_policies WHERE performance_id = 900 ORDER BY venue_seat_id",
                Long.class);

        assertThat(excluded).containsExactly(100001L, 100251L, 100501L, 100751L);
    }

    @Test
    @DisplayName("회차 900은 판매 검증을 통과하고, 응답의 등급·가격이 시드와 같다")
    void scheduleIsOnSale() throws Exception {
        // A-1-2(VIP), A-4-1(R), A-8-1(S)
        validate(900, 1, 100002, 100076, 100176)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.performanceId").value(900))
                .andExpect(jsonPath("$.configVersion").value(1))
                .andExpect(jsonPath("$.seats[*].venueSeatId").value(contains(100002, 100076, 100176)))
                .andExpect(jsonPath("$.seats[*].venueSeatGradeId").value(contains(900, 901, 902)))
                .andExpect(jsonPath("$.seats[*].gradeName").value(contains("VIP", "R", "S")))
                .andExpect(jsonPath("$.seats[*].basePrice").value(contains(150000, 120000, 90000)));
    }

    @Test
    @DisplayName("판매 제외 좌석은 판매 검증에서 SEAT_NOT_SELLABLE이다")
    void excludedSeatIsRejected() throws Exception {
        validate(900, 1, 100001)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.reasonCode").value("SEAT_NOT_SELLABLE"));
    }

    @Test
    @DisplayName("seed-load.sql 뒤에도 시퀀스가 맞춰져 다음 자동 ID가 시드 ID와 겹치지 않는다")
    void sequencesFollowLoadSeed() {
        Long nextVenueId = jdbcTemplate.queryForObject("""
                INSERT INTO p_venues (name, address, region, created_at, created_by, updated_at, updated_by)
                VALUES ('새 공연장', '주소', '서울', LOCALTIMESTAMP, 'SYSTEM', LOCALTIMESTAMP, 'SYSTEM')
                RETURNING id
                """, Long.class);
        Long nextSeatId = jdbcTemplate.queryForObject("""
                INSERT INTO p_venue_seats (venue_id, venue_seat_grade_id, section, row_label, seat_number,
                                           created_at, created_by, updated_at, updated_by)
                VALUES (900, 900, 'E', '1', 1, LOCALTIMESTAMP, 'SYSTEM', LOCALTIMESTAMP, 'SYSTEM')
                RETURNING id
                """, Long.class);

        assertThat(nextVenueId).isEqualTo(901L);
        assertThat(nextSeatId).isEqualTo(101001L);
    }

    private ResultActions validate(long scheduleId, long configVersion, long... venueSeatIds) throws Exception {
        StringBuilder ids = new StringBuilder();
        for (long id : venueSeatIds) {
            ids.append(ids.isEmpty() ? "" : ",").append(id);
        }
        return mockMvc.perform(post("/internal/schedules/" + scheduleId + "/sale-validations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"configVersion\":" + configVersion + ",\"venueSeatIds\":[" + ids + "]}"));
    }

    private Long venueSeatId(String section, String rowLabel, int seatNumber) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM p_venue_seats WHERE venue_id = 900 AND section = ? AND row_label = ? AND seat_number = ?",
                Long.class,
                section,
                rowLabel,
                seatNumber);
    }
}
