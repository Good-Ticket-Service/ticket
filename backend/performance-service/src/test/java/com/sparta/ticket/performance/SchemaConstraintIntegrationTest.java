package com.sparta.ticket.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.performance.support.PerformanceIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;

/**
 * schema.sql의 부분 UNIQUE와 CHECK 제약이 실제 PostgreSQL에서 동작하는지 확인한다.
 * 기준 데이터는 sql/public-performance-fixture.sql이다(공연장 1의 등급 1 VIP·2 R, 공연 10의 회차 12-01·12-02 19:00, 삭제 회차 12-31 19:00).
 */
@Sql(
        value = {"/sql/truncate.sql", "/sql/public-performance-fixture.sql"},
        config = @SqlConfig(encoding = "UTF-8"))
class SchemaConstraintIntegrationTest extends PerformanceIntegrationTest {

    private static final String AUDIT = "'2026-10-28 09:00:00', 'SYSTEM', '2026-10-28 09:00:00', 'SYSTEM'";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 픽스처가 ID를 직접 넣었으므로, 자동 ID가 기존 행과 겹쳐 PK 위반이 나지 않게 시퀀스를 맞춘다. */
    @BeforeEach
    void syncSequences() {
        for (String table :
                new String[] {"p_venue_seat_grades", "p_venue_seats", "p_performance_grade_prices", "p_schedules"}) {
            jdbcTemplate.execute("SELECT setval(pg_get_serial_sequence('%s', 'id'), (SELECT max(id) FROM %s))"
                    .formatted(table, table));
        }
    }

    @Test
    @DisplayName("같은 공연장의 미삭제 등급 이름은 중복될 수 없고, 소프트 삭제한 뒤에는 같은 이름을 다시 쓸 수 있다")
    void seatGradeNameIsUniqueAmongActiveRows() {
        assertThatThrownBy(() -> insertGrade(1, "R"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_p_venue_seat_grades_venue_name");

        jdbcTemplate.update(
                "UPDATE p_venue_seat_grades SET deleted_at = LOCALTIMESTAMP, deleted_by = 'SYSTEM' WHERE id = 2");
        insertGrade(1, "R");

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM p_venue_seat_grades WHERE venue_id = 1 AND name = 'R'", Integer.class))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("같은 공연의 미삭제 회차 시작 시각은 중복될 수 없고, 삭제된 회차와는 겹쳐도 된다")
    void scheduleStartIsUniqueAmongActiveRows() {
        assertThatThrownBy(() -> insertSchedule("2026-12-01 19:00:00", "2026-11-10 14:00:00", "2026-12-01 18:00:00"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_p_schedules_start");

        insertSchedule("2026-12-31 19:00:00", "2026-11-10 14:00:00", "2026-12-31 18:00:00");
    }

    @Test
    @DisplayName("가격·좌석 번호는 0일 수 없다")
    void rejectsNonPositiveValues() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                        INSERT INTO p_performance_grade_prices (performance_id, venue_seat_grade_id, price,
                            created_at, created_by, updated_at, updated_by)
                        VALUES (12, 1, 0, %s)
                        """.formatted(AUDIT)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_p_performance_grade_prices_price");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                        INSERT INTO p_venue_seats (venue_id, venue_seat_grade_id, section, row_label, seat_number,
                            created_at, created_by, updated_at, updated_by)
                        VALUES (1, 1, 'A', '1', 0, %s)
                        """.formatted(AUDIT)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_p_venue_seats_seat_number");
    }

    @Test
    @DisplayName("예매 시각은 오픈 < 마감 <= 공연 시작 순서여야 한다")
    void rejectsInvalidBookingPeriod() {
        // 오픈이 마감과 같다
        assertThatThrownBy(() -> insertSchedule("2026-12-10 19:00:00", "2026-12-10 18:00:00", "2026-12-10 18:00:00"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_p_schedules_booking_period");
        // 마감이 공연 시작보다 늦다
        assertThatThrownBy(() -> insertSchedule("2026-12-10 19:00:00", "2026-11-10 14:00:00", "2026-12-10 19:30:00"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_p_schedules_booking_period");

        // 마감과 공연 시작이 같은 것은 허용한다
        insertSchedule("2026-12-10 19:00:00", "2026-11-10 14:00:00", "2026-12-10 19:00:00");
    }

    private void insertGrade(long venueId, String name) {
        jdbcTemplate.update(
                "INSERT INTO p_venue_seat_grades (venue_id, name, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, " + AUDIT + ")",
                venueId,
                name);
    }

    /** 공연 10에 회차를 넣는다. 시각은 'yyyy-MM-dd HH:mm:ss' 형식의 KST다. */
    private void insertSchedule(String startAt, String bookingOpenAt, String bookingCloseAt) {
        jdbcTemplate.update("""
                INSERT INTO p_schedules (performance_id, start_at, booking_open_at, booking_close_at,
                    seat_setup_request_id, created_at, created_by, updated_at, updated_by)
                VALUES (10, CAST(? AS TIMESTAMP), CAST(? AS TIMESTAMP), CAST(? AS TIMESTAMP), 'req-new', %s)
                """.formatted(AUDIT), startAt, bookingOpenAt, bookingCloseAt);
    }
}
