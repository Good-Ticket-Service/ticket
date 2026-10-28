package com.sparta.ticket.reservation.seat.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.common.config.HeaderAuditorAware;
import com.sparta.ticket.reservation.booking.support.MutableClock;
import com.sparta.ticket.reservation.seat.application.facade.SeatFacade;
import com.sparta.ticket.reservation.seat.application.facade.SeatLock;
import com.sparta.ticket.reservation.seat.application.facade.SeatState;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeat;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeatRepository;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/** booking이 확정·만료·취소에서 부르는 SeatFacade. 이 예약의 좌석만 바뀌고, 바뀐 행 수로 결과를 알린다. */
class SeatFacadeIntegrationTest extends ReservationIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 20, 0);
    private static final Long RESERVATION = 7001L;
    private static final Long OTHER_RESERVATION = 7002L;

    @Autowired
    private SeatFacade seatFacade;

    @Autowired
    private ScheduleSeatRepository scheduleSeatRepository;

    @Autowired
    private MutableClock clock;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long nextVenueSeatId = 100;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM p_schedule_seats");
        clock.set(NOW);
    }

    private Long available() {
        return scheduleSeatRepository
                .save(ScheduleSeat.create(1L, nextVenueSeatId++, 7L, "VIP", 150_000, "A", "1", 1, true, 1L))
                .getId();
    }

    private Long heldBy(Long reservationId) {
        Long id = available();
        jdbcTemplate.update(
                "UPDATE p_schedule_seats SET status = 'HOLD', reservation_id = ?, hold_expires_at = ? WHERE id = ?",
                reservationId,
                NOW.plusMinutes(10),
                id);
        return id;
    }

    private Long soldTo(Long reservationId) {
        Long id = available();
        jdbcTemplate.update(
                "UPDATE p_schedule_seats SET status = 'SOLD', reservation_id = ? WHERE id = ?", reservationId, id);
        return id;
    }

    private Map<String, Object> row(Long id) {
        return jdbcTemplate.queryForMap(
                "SELECT status, reservation_id, hold_expires_at, updated_at, updated_by FROM p_schedule_seats WHERE id = ?",
                id);
    }

    @Test
    @DisplayName("lockSeats는 넘긴 순서와 상관없이 id 오름차순으로 잠그고 상태·점유 예약·만료 시각을 돌려준다. 없는 좌석은 빠진다")
    void lockSeatsInIdOrder() {
        Long free = available();
        Long held = heldBy(RESERVATION);
        Long sold = soldTo(OTHER_RESERVATION);

        List<SeatLock> locks =
                transactionTemplate.execute(status -> seatFacade.lockSeats(List.of(sold, -1L, free, held)));

        assertThat(locks)
                .containsExactly(
                        new SeatLock(free, SeatState.AVAILABLE, null, null),
                        new SeatLock(held, SeatState.HOLD, RESERVATION, NOW.plusMinutes(10)),
                        new SeatLock(sold, SeatState.SOLD, OTHER_RESERVATION, null));
    }

    @Test
    @DisplayName("sell은 이 예약의 HOLD만 SOLD로 바꾸고 만료 시각을 비운다. 감사 컬럼은 공용 Clock과 SYSTEM으로 채운다")
    void sellOwnHold() {
        Long mine = heldBy(RESERVATION);
        clock.set(NOW.plusMinutes(3));

        int changed = transactionTemplate.execute(status -> seatFacade.sell(List.of(mine), RESERVATION));

        assertThat(changed).isEqualTo(1);
        Map<String, Object> row = row(mine);
        assertThat(row.get("status")).isEqualTo("SOLD");
        assertThat(row.get("reservation_id")).isEqualTo(RESERVATION);
        assertThat(row.get("hold_expires_at")).isNull();
        assertThat(((Timestamp) row.get("updated_at")).toLocalDateTime()).isEqualTo(NOW.plusMinutes(3));
        assertThat(row.get("updated_by")).isEqualTo(HeaderAuditorAware.SYSTEM);
    }

    @Test
    @DisplayName("sell은 다른 예약의 HOLD, AVAILABLE, SOLD를 바꾸지 않고 바뀐 행 수만큼만 돌려준다")
    void sellSkipsOthers() {
        Long mine = heldBy(RESERVATION);
        Long others = heldBy(OTHER_RESERVATION);
        Long free = available();
        Long alreadySold = soldTo(RESERVATION);

        int changed = transactionTemplate.execute(
                status -> seatFacade.sell(List.of(mine, others, free, alreadySold), RESERVATION));

        assertThat(changed).isEqualTo(1);
        assertThat(row(others).get("status")).isEqualTo("HOLD");
        assertThat(row(others).get("reservation_id")).isEqualTo(OTHER_RESERVATION);
        assertThat(row(free).get("status")).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("release는 이 예약의 HOLD·SOLD를 AVAILABLE로 돌리고 예약과 만료 시각을 비운다")
    void releaseOwnSeats() {
        Long held = heldBy(RESERVATION);
        Long sold = soldTo(RESERVATION);

        int changed = transactionTemplate.execute(status -> seatFacade.release(List.of(held, sold), RESERVATION));

        assertThat(changed).isEqualTo(2);
        for (Long id : List.of(held, sold)) {
            Map<String, Object> row = row(id);
            assertThat(row.get("status")).isEqualTo("AVAILABLE");
            assertThat(row.get("reservation_id")).isNull();
            assertThat(row.get("hold_expires_at")).isNull();
            assertThat(row.get("updated_by")).isEqualTo(HeaderAuditorAware.SYSTEM);
        }
    }

    @Test
    @DisplayName("release는 이미 다른 예약에 넘어간 좌석을 건드리지 않는다. 늦게 온 만료 정리가 새 선점을 지우지 못한다")
    void releaseKeepsOtherReservation() {
        Long reHeld = heldBy(OTHER_RESERVATION);

        int changed = transactionTemplate.execute(status -> seatFacade.release(List.of(reHeld), RESERVATION));

        assertThat(changed).isZero();
        assertThat(row(reHeld).get("status")).isEqualTo("HOLD");
        assertThat(row(reHeld).get("reservation_id")).isEqualTo(OTHER_RESERVATION);
    }

    @Test
    @DisplayName("빈 목록이면 아무것도 바꾸지 않는다")
    void emptyIds() {
        transactionTemplate.executeWithoutResult(status -> {
            assertThat(seatFacade.lockSeats(List.of())).isEmpty();
            assertThat(seatFacade.sell(List.of(), RESERVATION)).isZero();
            assertThat(seatFacade.release(List.of(), RESERVATION)).isZero();
        });
    }

    @Test
    @DisplayName("논리 삭제된 좌석은 sell·release로 바뀌지 않고 행 수에도 들어가지 않는다")
    void deletedSeatsUnchanged() {
        Long deletedHold = heldBy(RESERVATION);
        Long deletedSold = soldTo(RESERVATION);
        jdbcTemplate.update(
                "UPDATE p_schedule_seats SET deleted_at = ?, deleted_by = 'SYSTEM' WHERE id IN (?, ?)",
                NOW,
                deletedHold,
                deletedSold);

        transactionTemplate.executeWithoutResult(status -> {
            assertThat(seatFacade.sell(List.of(deletedHold), RESERVATION)).isZero();
            assertThat(seatFacade.release(List.of(deletedSold), RESERVATION)).isZero();
        });

        assertThat(row(deletedHold).get("status")).isEqualTo("HOLD");
        assertThat(row(deletedSold).get("status")).isEqualTo("SOLD");
    }

    @Test
    @DisplayName("같은 id가 여러 번 와도 한 번으로 센다")
    void duplicateIdsCountOnce() {
        Long mine = heldBy(RESERVATION);

        int changed = transactionTemplate.execute(status -> seatFacade.sell(List.of(mine, mine), RESERVATION));

        assertThat(changed).isEqualTo(1);
    }

    @Test
    @DisplayName("세 메서드 모두 트랜잭션 밖에서 부르면 IllegalTransactionStateException이다")
    void requiresTransaction() {
        Long held = heldBy(RESERVATION);

        assertThatThrownBy(() -> seatFacade.lockSeats(List.of(held)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> seatFacade.sell(List.of(held), RESERVATION))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> seatFacade.release(List.of(held), RESERVATION))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(row(held).get("status")).isEqualTo("HOLD");
    }

    @Test
    @DisplayName("빈 목록이어도 트랜잭션 밖에서 부르면 IllegalTransactionStateException이다")
    void requiresTransactionEvenForEmptyIds() {
        assertThatThrownBy(() -> seatFacade.lockSeats(List.of())).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> seatFacade.sell(List.of(), RESERVATION))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> seatFacade.release(List.of(), RESERVATION))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
