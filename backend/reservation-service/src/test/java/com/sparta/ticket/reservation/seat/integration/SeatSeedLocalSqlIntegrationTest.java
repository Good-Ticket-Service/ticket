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
 * local 회차 좌석 시드(src/main/resources/db/seed-local.sql, #124)를 실제 seat.sql로 만든 테이블에 넣어 확인한다.
 *
 * 확인하는 것
 *   - local은 Hibernate가 만든 테이블(CHECK·UK가 빠질 수 있음)에 시드를 넣는다. 제약이 더 엄격한 seat.sql 위에서도 들어가는지 본다.
 *     통과하면 같은 시드를 dev처럼 seat.sql로 만든 DB에도 넣을 수 있다(공연 SeedLocalSqlIntegrationTest와 같은 취지).
 *   - 회차별 좌석 수, 판매 제외 좌석, 등급별 가격, 공연장 좌석 ID 공식이 시드 주석대로인지 본다.
 *   - 시드 좌석으로 선택 가능 좌석 조회가 200으로 응답하는지 본다.
 *
 * 확인하지 못하는 것: 값이 공연 local 시드와 같은지는 서비스가 달라 테스트로 대조할 수 없다. 2026-10-29에 local DB에서 공연 시드의
 * p_venue_seats를 조회해 ID 순서를 확인했고, 그 결과를 시드 주석과 아래 기대값에 적었다. 공연 시드가 바뀌면 이 테스트도 함께 고친다.
 */
class SeatSeedLocalSqlIntegrationTest extends ReservationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    /**
     * 다른 테스트가 남긴 좌석·예약을 지운 뒤 시드를 넣는다. 시드는 빈 테이블을 전제로 하므로(다시 넣으면 UK 위반) 매 테스트 비운다.
     * local 기동과 같은 파일을 같은 인코딩(UTF-8)으로 실행한다.
     */
    @BeforeEach
    void loadSeed() {
        jdbcTemplate.update("DELETE FROM p_schedule_seats");
        jdbcTemplate.update("DELETE FROM p_tickets");
        jdbcTemplate.update("DELETE FROM p_reservations");
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(new ClassPathResource("db/seed-local.sql"));
        populator.setSqlScriptEncoding(StandardCharsets.UTF_8.name());
        populator.execute(dataSource);
    }

    @Test
    @DisplayName("회차 1~3에 40석씩 들어가고, 모두 빈 좌석이며 구성 버전은 1이다. 회차마다 공연장 좌석 1~40을 한 번씩 쓴다")
    void insertsFortySeatsPerSchedule() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT schedule_id, COUNT(*) AS seats, COUNT(DISTINCT venue_seat_id) AS venue_seats,
                       MIN(venue_seat_id) AS min_id, MAX(venue_seat_id) AS max_id,
                       COUNT(*) FILTER (WHERE status = 'AVAILABLE' AND reservation_id IS NULL AND hold_expires_at IS NULL)
                           AS available,
                       COUNT(*) FILTER (WHERE config_version = 1) AS version_one
                  FROM p_schedule_seats
                 GROUP BY schedule_id
                 ORDER BY schedule_id
                """);

        assertThat(rows)
                .extracting(row -> ((Number) row.get("schedule_id")).longValue())
                .containsExactly(1L, 2L, 3L);
        for (Map<String, Object> row : rows) {
            assertThat(((Number) row.get("seats")).intValue()).isEqualTo(40);
            assertThat(((Number) row.get("venue_seats")).intValue()).isEqualTo(40);
            assertThat(((Number) row.get("min_id")).longValue()).isEqualTo(1L);
            assertThat(((Number) row.get("max_id")).longValue()).isEqualTo(40L);
            assertThat(((Number) row.get("available")).intValue()).isEqualTo(40);
            assertThat(((Number) row.get("version_one")).intValue()).isEqualTo(40);
        }
    }

    @Test
    @DisplayName("판매 제외는 회차마다 A구역 1열 1번(공연장 좌석 1) 하나이고, VIP 가격을 가진다")
    void excludedSeatIsTheOperationSeat() {
        List<Map<String, Object>> excluded = jdbcTemplate.queryForList("""
                SELECT schedule_id, venue_seat_id, section, row_label, seat_number, base_price
                  FROM p_schedule_seats
                 WHERE NOT is_sellable
                 ORDER BY schedule_id
                """);

        assertThat(excluded).hasSize(3);
        for (Map<String, Object> seat : excluded) {
            assertThat(((Number) seat.get("venue_seat_id")).longValue()).isEqualTo(1L);
            assertThat(seat.get("section")).isEqualTo("A");
            assertThat(seat.get("row_label")).isEqualTo("1");
            assertThat(((Number) seat.get("seat_number")).intValue()).isEqualTo(1);
            assertThat(((Number) seat.get("base_price")).intValue()).isEqualTo(170_000);
        }
    }

    @Test
    @DisplayName("한 회차의 등급별 좌석 수·가격이 공연 시드와 같다: VIP 10석 170000, R 10석 140000, S 20석 110000")
    void gradesAndPrices() {
        List<Map<String, Object>> grades = jdbcTemplate.queryForList("""
                SELECT venue_seat_grade_id, grade_name, base_price, COUNT(*) AS seats
                  FROM p_schedule_seats
                 WHERE schedule_id = 1
                 GROUP BY venue_seat_grade_id, grade_name, base_price
                 ORDER BY venue_seat_grade_id
                """);

        assertThat(grades)
                .extracting(
                        row -> ((Number) row.get("venue_seat_grade_id")).longValue(),
                        row -> row.get("grade_name"),
                        row -> ((Number) row.get("base_price")).intValue(),
                        row -> ((Number) row.get("seats")).intValue())
                .containsExactly(
                        tuple(1L, "VIP", 170_000, 10), tuple(2L, "R", 140_000, 10), tuple(3L, "S", 110_000, 20));
    }

    @Test
    @DisplayName("공연장 좌석 ID는 공연 시드 순서(좌석 번호 바깥, 열 안쪽)를 따른다: A-2-1=2, B-1-1=3, A-1-2=5, B-2-10=40")
    void venueSeatIdFormula() {
        assertThat(venueSeatId("A", "2", 1)).isEqualTo(2L);
        assertThat(venueSeatId("B", "1", 1)).isEqualTo(3L);
        assertThat(venueSeatId("A", "1", 2)).isEqualTo(5L);
        assertThat(venueSeatId("B", "2", 10)).isEqualTo(40L);
    }

    @Test
    @DisplayName("시드 좌석으로 선택 가능 좌석 조회가 200이고, 40석 중 판매 제외 1석을 뺀 39석이 선택 가능하다")
    void selectableSeatsApi() throws Exception {
        mockMvc.perform(get("/api/seats/schedules/{scheduleId}", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configVersion").value(1))
                .andExpect(jsonPath("$.data.seats.length()").value(40))
                .andExpect(jsonPath("$.data.seats[?(@.selectable == true)]", hasSize(39)));
    }

    private Long venueSeatId(String section, String rowLabel, int seatNumber) {
        return jdbcTemplate.queryForObject(
                "SELECT venue_seat_id FROM p_schedule_seats"
                        + " WHERE schedule_id = 1 AND section = ? AND row_label = ? AND seat_number = ?",
                Long.class,
                section,
                rowLabel,
                seatNumber);
    }
}
