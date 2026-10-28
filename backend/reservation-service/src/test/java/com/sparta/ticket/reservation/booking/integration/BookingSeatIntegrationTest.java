package com.sparta.ticket.reservation.booking.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.common.auth.UserContext;
import com.sparta.ticket.reservation.booking.application.HoldExpiryService;
import com.sparta.ticket.reservation.booking.application.ReservationPaymentService;
import com.sparta.ticket.reservation.booking.application.dto.CheckoutCommand;
import com.sparta.ticket.reservation.booking.application.dto.ConfirmCommand;
import com.sparta.ticket.reservation.booking.application.facade.BookingFacade;
import com.sparta.ticket.reservation.booking.application.port.ConfirmOutcome;
import com.sparta.ticket.reservation.booking.domain.ReservationStatus;
import com.sparta.ticket.reservation.booking.infrastructure.seat.SeatFacadeAdapter;
import com.sparta.ticket.reservation.booking.support.FakePaymentPort;
import com.sparta.ticket.reservation.booking.support.FakeSeatPort;
import com.sparta.ticket.reservation.booking.support.MutableClock;
import com.sparta.ticket.reservation.booking.support.TestIds;
import com.sparta.ticket.reservation.booking.support.TestSeats;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** booking의 확정·만료가 SeatFacade를 거쳐 실제 좌석 행까지 바꾸는지 본다. 좌석은 seat의 선점이 남기는 모양으로 직접 넣는다. */
class BookingSeatIntegrationTest extends ReservationIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 20, 0);

    @Autowired
    private BookingFacade bookingFacade;

    @Autowired
    private ReservationPaymentService paymentService;

    @Autowired
    private HoldExpiryService holdExpiryService;

    @Autowired
    private SeatFacadeAdapter seatFacadeAdapter;

    @Autowired
    private FakeSeatPort seats;

    @Autowired
    private FakePaymentPort payment;

    @Autowired
    private MutableClock clock;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final UserContext user = new UserContext(TestIds.next(), "hong01", Role.USER);
    private final Long scheduleId = TestIds.next();
    private long nextVenueSeatId = 1;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM p_tickets");
        jdbcTemplate.update("DELETE FROM p_reservations");
        jdbcTemplate.update("DELETE FROM p_schedule_seats");
        clock.set(NOW);
        payment.reset();
        seats.reset();
        seats.useReal(seatFacadeAdapter);
    }

    @AfterEach
    void tearDown() {
        // 컨텍스트를 다른 테스트와 함께 쓰므로 실제 좌석 연결을 풀어 둔다.
        seats.reset();
    }

    private Long seat() {
        return jdbcTemplate.queryForObject("""
                INSERT INTO p_schedule_seats (schedule_id, venue_seat_id, venue_seat_grade_id, grade_name, base_price,
                    section, row_label, seat_number, is_sellable, config_version,
                    created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, 1, 'VIP', 10000, 'A', '1', ?, TRUE, 1, ?, 'test', ?, 'test')
                RETURNING id
                """, Long.class, scheduleId, nextVenueSeatId, nextVenueSeatId++, NOW, NOW);
    }

    private void holdSeats(Long reservationId, List<Long> seatIds) {
        for (Long seatId : seatIds) {
            jdbcTemplate.update(
                    "UPDATE p_schedule_seats SET status = 'HOLD', reservation_id = ?, hold_expires_at = ? WHERE id = ?",
                    reservationId,
                    NOW.plusMinutes(10),
                    seatId);
        }
    }

    /** seat의 선점처럼 예약을 만들고 좌석을 그 예약의 HOLD로 둔다. */
    private Long holdWithSeats(List<Long> seatIds) {
        Long reservationId = transactionTemplate.execute(status -> bookingFacade.hold(
                user.userId(), scheduleId, TestSeats.priced(seatIds.toArray(Long[]::new)), NOW.plusMinutes(10)));
        holdSeats(reservationId, seatIds);
        return reservationId;
    }

    private ReservationStatus confirm(Long reservationId) {
        String orderId = paymentService
                .checkout(user, reservationId, new CheckoutCommand(null, "TOSS"))
                .orderId();
        return paymentService
                .confirm(user, reservationId, new ConfirmCommand(orderId, "pk-1"))
                .status();
    }

    private Map<String, Object> seatRow(Long seatId) {
        return jdbcTemplate.queryForMap(
                "SELECT status, reservation_id, hold_expires_at FROM p_schedule_seats WHERE id = ?", seatId);
    }

    private boolean lock(List<Long> seatIds, Long reservationId) {
        return Boolean.TRUE.equals(
                transactionTemplate.execute(status -> seatFacadeAdapter.lockSeats(seatIds, reservationId)));
    }

    private double lateSuccessCount() {
        return meterRegistry.counter("reservation.payment.late_success").count();
    }

    @Test
    @DisplayName("결제가 성공하면 예약이 CONFIRMED가 되고 좌석이 SOLD가 된다")
    void confirmSellsSeats() {
        List<Long> seatIds = List.of(seat(), seat());
        Long reservationId = holdWithSeats(seatIds);

        assertThat(confirm(reservationId)).isEqualTo(ReservationStatus.CONFIRMED);

        for (Long seatId : seatIds) {
            Map<String, Object> row = seatRow(seatId);
            assertThat(row.get("status")).isEqualTo("SOLD");
            assertThat(row.get("reservation_id")).isEqualTo(reservationId);
            assertThat(row.get("hold_expires_at")).isNull();
        }
    }

    @Test
    @DisplayName("선점 시간이 지나면 만료 스케줄러가 예약을 EXPIRED로 바꾸고 좌석을 AVAILABLE로 돌려준다")
    void expiryReleasesSeats() {
        Long seatId = seat();
        Long reservationId = holdWithSeats(List.of(seatId));
        clock.advance(Duration.ofMinutes(10));

        assertThat(holdExpiryService.expireDue(10, Duration.ofSeconds(5))).isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM p_reservations WHERE reservation_id = ?", String.class, reservationId))
                .isEqualTo("EXPIRED");
        Map<String, Object> row = seatRow(seatId);
        assertThat(row.get("status")).isEqualTo("AVAILABLE");
        assertThat(row.get("reservation_id")).isNull();
    }

    @Test
    @DisplayName("선점한 좌석 하나가 논리 삭제돼도 만료는 남은 좌석을 돌려주고 끝난다. 같은 예약이 만료 후보 맨 앞에 남지 않는다")
    void expiryIgnoresDeletedSeat() {
        Long kept = seat();
        Long deleted = seat();
        Long reservationId = holdWithSeats(List.of(kept, deleted));
        jdbcTemplate.update(
                "UPDATE p_schedule_seats SET deleted_at = ?, deleted_by = 'test' WHERE id = ?", NOW, deleted);
        clock.advance(Duration.ofMinutes(10));

        assertThat(holdExpiryService.expireDue(10, Duration.ofSeconds(5))).isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM p_reservations WHERE reservation_id = ?", String.class, reservationId))
                .isEqualTo("EXPIRED");
        assertThat(seatRow(kept).get("status")).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("결제가 실패했는데 선점 시간이 지났으면 예약은 EXPIRED, 좌석은 AVAILABLE이 된다")
    void failureAfterExpiryReleasesSeats() {
        Long seatId = seat();
        Long reservationId = holdWithSeats(List.of(seatId));
        payment.confirmAnswers(() -> {
            clock.advance(Duration.ofMinutes(11));
            return ConfirmOutcome.failed("EXPIRED");
        });

        assertThat(confirm(reservationId)).isEqualTo(ReservationStatus.EXPIRED);

        assertThat(seatRow(seatId).get("status")).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("확정할 좌석을 이 예약이 잡고 있지 않으면 확정하지 않고 결제 중으로 남겨 환불 대상으로 센다")
    void doesNotConfirmWhenSeatsAreNotHeld() {
        Long seatId = seat();
        Long reservationId = holdWithSeats(List.of(seatId));
        holdSeats(TestIds.next(), List.of(seatId));
        double lateBefore = lateSuccessCount();

        assertThat(confirm(reservationId)).isEqualTo(ReservationStatus.PAYMENT_IN_PROGRESS);

        assertThat(seatRow(seatId).get("status")).as("다른 예약의 선점은 그대로다").isEqualTo("HOLD");
        assertThat(seats.sold).isEmpty();
        assertThat(lateSuccessCount()).isEqualTo(lateBefore + 1);
    }

    @Test
    @DisplayName("판매할 좌석을 다 바꾸지 못하면 예외로 되돌리고, 반환은 이 예약이 가진 좌석만 돌려준다")
    void adapterRejectsPartialChange() {
        Long mine = seat();
        Long other = seat();
        Long reservationId = TestIds.next();
        holdSeats(reservationId, List.of(mine));
        holdSeats(TestIds.next(), List.of(other));

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(
                        status -> seatFacadeAdapter.sell(List.of(mine, other), reservationId)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(seatRow(mine).get("status")).as("롤백돼 SOLD가 남지 않는다").isEqualTo("HOLD");

        transactionTemplate.executeWithoutResult(
                status -> seatFacadeAdapter.release(List.of(mine, other), reservationId));
        assertThat(seatRow(mine).get("status")).isEqualTo("AVAILABLE");
        assertThat(seatRow(other).get("status")).as("다른 예약의 좌석은 그대로다").isEqualTo("HOLD");
    }

    @Test
    @DisplayName("lockSeats는 좌석이 모두 있고 모두 이 예약의 HOLD일 때만 true다")
    void lockSeatsReportsOwnership() {
        Long mine = seat();
        Long free = seat();
        Long reservationId = TestIds.next();
        holdSeats(reservationId, List.of(mine));

        assertThat(lock(List.of(mine), reservationId)).isTrue();
        assertThat(lock(List.of(mine, free), reservationId)).isFalse();
        assertThat(lock(List.of(mine, -1L), reservationId))
                .as("없는 좌석이 섞이면 false")
                .isFalse();
    }
}
