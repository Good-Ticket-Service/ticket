package com.sparta.ticket.reservation.seat.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 부하 측정용 회차 좌석 시드(src/main/resources/db/seed-load.sql, #173)를 실제 seat.sql로 만든 테이블에 넣어 확인한다.
 *
 * 확인하는 것
 *   - local 기동과 같은 순서(seed-local.sql 다음)로 넣어도 seat.sql의 제약(CHECK·UK)을 어기지 않는다.
 *   - 회차 900의 좌석 수, 공연장 좌석 ID 공식, 등급별 좌석 수·가격, 판매 제외 좌석이 시드 주석대로다.
 *   - 1,000석 회차도 선택 가능 좌석 조회가 200으로 응답한다.
 *
 * 확인하지 못하는 것: 값이 공연 부하 시드와 같은지는 서비스가 달라 테스트로 대조할 수 없다. 공연 쪽은 SeedLoadSqlIntegrationTest가
 * 같은 공식·등급·가격·판매 제외를 확인한다. 하나를 바꾸면 두 시드와 두 테스트를 함께 고친다.
 */
class SeatSeedLoadSqlIntegrationTest extends ReservationIntegrationTest {

    private static final long SCHEDULE = 900L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    /** 다른 테스트가 남긴 좌석·예약을 지운 뒤 local 기동과 같은 순서로 두 시드를 넣는다. 시드는 빈 테이블을 전제로 한다. */
    @BeforeEach
    void loadSeeds() {
        jdbcTemplate.update("DELETE FROM p_schedule_seats");
        jdbcTemplate.update("DELETE FROM p_tickets");
        jdbcTemplate.update("DELETE FROM p_reservations");
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource("db/seed-local.sql"), new ClassPathResource("db/seed-load.sql"));
        populator.setSqlScriptEncoding(StandardCharsets.UTF_8.name());
        populator.execute(dataSource);
    }

    @Test
    @DisplayName("회차 900에 1,000석이 모두 빈 좌석·구성 버전 1로 들어가고, 공연장 좌석 100001~101000을 한 번씩 쓴다")
    void insertsThousandSeats() {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT COUNT(*) AS seats, COUNT(DISTINCT venue_seat_id) AS venue_seats,
                       MIN(venue_seat_id) AS min_id, MAX(venue_seat_id) AS max_id,
                       COUNT(*) FILTER (WHERE status = 'AVAILABLE' AND reservation_id IS NULL AND hold_expires_at IS NULL)
                           AS available,
                       COUNT(*) FILTER (WHERE config_version = 1) AS version_one
                  FROM p_schedule_seats
                 WHERE schedule_id = ?
                """, SCHEDULE);

        assertThat(((Number) row.get("seats")).intValue()).isEqualTo(1000);
        assertThat(((Number) row.get("venue_seats")).intValue()).isEqualTo(1000);
        assertThat(((Number) row.get("min_id")).longValue()).isEqualTo(100001L);
        assertThat(((Number) row.get("max_id")).longValue()).isEqualTo(101000L);
        assertThat(((Number) row.get("available")).intValue()).isEqualTo(1000);
        assertThat(((Number) row.get("version_one")).intValue()).isEqualTo(1000);
        // local 시드(회차 1~3, 40석씩)는 그대로다
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM p_schedule_seats WHERE schedule_id <> ?", Integer.class, SCHEDULE))
                .isEqualTo(120);
    }

    @Test
    @DisplayName(
            "공연장 좌석 ID = 100000 + 구역 순번 × 250 + (열 - 1) × 25 + 좌석 번호: A-1-1=100001, A-2-1=100026, B-1-1=100251, D-10-25=101000")
    void venueSeatIdFormula() {
        assertThat(venueSeatId("A", "1", 1)).isEqualTo(100001L);
        assertThat(venueSeatId("A", "2", 1)).isEqualTo(100026L);
        assertThat(venueSeatId("B", "1", 1)).isEqualTo(100251L);
        assertThat(venueSeatId("D", "10", 25)).isEqualTo(101000L);
    }

    @Test
    @DisplayName("등급별 좌석 수·가격이 공연 부하 시드와 같다: VIP 300석 150000, R 400석 120000, S 300석 90000")
    void gradesAndPrices() {
        List<Map<String, Object>> grades = jdbcTemplate.queryForList("""
                SELECT venue_seat_grade_id, grade_name, base_price, COUNT(*) AS seats
                  FROM p_schedule_seats
                 WHERE schedule_id = ?
                 GROUP BY venue_seat_grade_id, grade_name, base_price
                 ORDER BY venue_seat_grade_id
                """, SCHEDULE);

        assertThat(grades)
                .extracting(
                        row -> ((Number) row.get("venue_seat_grade_id")).longValue(),
                        row -> row.get("grade_name"),
                        row -> ((Number) row.get("base_price")).intValue(),
                        row -> ((Number) row.get("seats")).intValue())
                .containsExactly(
                        tuple(900L, "VIP", 150_000, 300),
                        tuple(901L, "R", 120_000, 400),
                        tuple(902L, "S", 90_000, 300));
    }

    @Test
    @DisplayName("판매 제외는 구역마다 1열 1번 4석이고, 공연 쪽 판매 제외 정책과 같은 공연장 좌석이다")
    void excludedSeats() {
        List<Long> excluded = jdbcTemplate.queryForList(
                "SELECT venue_seat_id FROM p_schedule_seats WHERE schedule_id = ? AND NOT is_sellable ORDER BY venue_seat_id",
                Long.class,
                SCHEDULE);

        assertThat(excluded).containsExactly(100001L, 100251L, 100501L, 100751L);
    }

    @Test
    @DisplayName("1,000석 회차도 선택 가능 좌석 조회가 200이고, 판매 제외 4석을 뺀 996석이 선택 가능하다")
    void selectableSeatsApi() throws Exception {
        mockMvc.perform(get("/api/seats/schedules/{scheduleId}", SCHEDULE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configVersion").value(1))
                .andExpect(jsonPath("$.data.seats.length()").value(1000))
                .andExpect(jsonPath("$.data.seats[?(@.selectable == true)]", hasSize(996)));
    }

    private Long venueSeatId(String section, String rowLabel, int seatNumber) {
        return jdbcTemplate.queryForObject(
                "SELECT venue_seat_id FROM p_schedule_seats"
                        + " WHERE schedule_id = ? AND section = ? AND row_label = ? AND seat_number = ?",
                Long.class,
                SCHEDULE,
                section,
                rowLabel,
                seatNumber);
    }
}
