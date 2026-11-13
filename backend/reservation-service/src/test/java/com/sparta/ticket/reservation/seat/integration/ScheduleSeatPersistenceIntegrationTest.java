package com.sparta.ticket.reservation.seat.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.reservation.seat.domain.ScheduleSeat;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeatRepository;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** 회차 좌석 테이블이 seat.sql로 만들어지고, 제약과 잠금 조회가 DB에서 동작한다. */
class ScheduleSeatPersistenceIntegrationTest extends ReservationIntegrationTest {

    private static final Long SCHEDULE = 1L;

    @Autowired
    private ScheduleSeatRepository scheduleSeatRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM p_schedule_seats");
    }

    private Long save(Long venueSeatId) {
        return scheduleSeatRepository
                .save(ScheduleSeat.create(SCHEDULE, venueSeatId, 7L, "VIP", 150_000, "A", "1", 1, true, 1L))
                .getId();
    }

    @Test
    @DisplayName("테이블은 backend/sql/reservation-service/seat.sql로 만들어진다(이름 붙인 제약과 인덱스가 있다)")
    void schemaFromSqlFile() {
        assertThat(jdbcTemplate.queryForList(
                        "SELECT conname FROM pg_constraint WHERE conrelid = 'p_schedule_seats'::regclass",
                        String.class))
                .contains(
                        "uk_p_schedule_seats_schedule_venue_seat",
                        "ck_p_schedule_seats_status",
                        "ck_p_schedule_seats_base_price",
                        "ck_p_schedule_seats_seat_number",
                        "ck_p_schedule_seats_config_version",
                        "ck_p_schedule_seats_holder");
        assertThat(jdbcTemplate.queryForList(
                        "SELECT indexname FROM pg_indexes WHERE tablename = 'p_schedule_seats'", String.class))
                .contains(
                        "ix_p_schedule_seats_selectable",
                        "ix_p_schedule_seats_hold_expiry",
                        "ix_p_schedule_seats_reservation");
    }

    @Test
    @DisplayName("같은 회차에 같은 물리 좌석을 두 번 만들 수 없다")
    void uniqueScheduleVenueSeat() {
        save(100L);

        assertThatThrownBy(() -> scheduleSeatRepository.saveAndFlush(
                        ScheduleSeat.create(SCHEDULE, 100L, 7L, "VIP", 150_000, "A", "1", 1, true, 1L)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_p_schedule_seats_schedule_venue_seat");
    }

    @Test
    @DisplayName("HOLD인데 점유 예약이 없는 행은 DB가 거부한다")
    void holderCheck() {
        Long id = save(100L);

        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE p_schedule_seats SET status = 'HOLD', hold_expires_at = now() WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_p_schedule_seats_holder");
    }

    @Test
    @DisplayName("잠금 조회는 넘긴 순서와 상관없이 id 오름차순이고, 없는 id와 삭제된 좌석은 빠진다")
    void findAllByIdsForUpdateInIdOrder() {
        Long first = save(100L);
        Long second = save(101L);
        Long third = save(102L);
        Long deleted = save(103L);
        jdbcTemplate.update(
                "UPDATE p_schedule_seats SET deleted_at = now(), deleted_by = 'SYSTEM' WHERE id = ?", deleted);

        List<Long> locked = transactionTemplate.execute(status ->
                scheduleSeatRepository.findAllByIdsForUpdate(List.of(third, deleted, first, -1L, second)).stream()
                        .map(ScheduleSeat::getId)
                        .toList());

        assertThat(locked).containsExactly(first, second, third);
    }

    @Test
    @DisplayName("잠금 조회를 트랜잭션 밖에서 부르면 실패한다. 잠금이 바로 풀리는 호출을 막는다")
    void findAllByIdsForUpdateNeedsTransaction() {
        Long id = save(100L);

        assertThatThrownBy(() -> scheduleSeatRepository.findAllByIdsForUpdate(List.of(id)))
                .hasMessageContaining("transaction");
    }
}
