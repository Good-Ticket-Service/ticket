package com.sparta.ticket.reservation.booking.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.reservation.booking.application.ChangeStamps;
import com.sparta.ticket.reservation.booking.application.facade.BookingFacade;
import com.sparta.ticket.reservation.booking.application.facade.ReservationState;
import com.sparta.ticket.reservation.booking.application.facade.ReservationView;
import com.sparta.ticket.reservation.booking.domain.ReservationRepository;
import com.sparta.ticket.reservation.booking.support.MutableClock;
import com.sparta.ticket.reservation.booking.support.TestIds;
import com.sparta.ticket.reservation.booking.support.TestSeats;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/** 좌석 선점·조회가 부르는 BookingFacade 메서드(countActiveSeats, findActiveHold, expireIfDue, findPaymentInProgress). */
class BookingFacadeSeatQueryIntegrationTest extends ReservationIntegrationTest {

    @Autowired
    private BookingFacade bookingFacade;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ChangeStamps changeStamps;

    @Autowired
    private MutableClock clock;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final Long userId = TestIds.next();

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM p_tickets");
        jdbcTemplate.update("DELETE FROM p_reservations");
        clock.set(LocalDateTime.of(2026, 11, 1, 20, 0));
    }

    private Long hold(Long user, Long scheduleId, Duration holdFor, Long... seatIds) {
        return holdUntil(user, scheduleId, clock.now().plus(holdFor), seatIds);
    }

    private Long holdUntil(Long user, Long scheduleId, LocalDateTime holdExpiresAt, Long... seatIds) {
        return transactionTemplate.execute(
                status -> bookingFacade.hold(user, scheduleId, TestSeats.priced(seatIds), holdExpiresAt));
    }

    private void checkout(Long reservationId) {
        transactionTemplate.execute(status -> reservationRepository.recordCheckout(
                reservationId, userId, null, orderId(reservationId), 10_000L, changeStamps.stamp()));
    }

    private int startPayment(Long reservationId) {
        return transactionTemplate.execute(status -> reservationRepository.startPayment(
                reservationId, userId, orderId(reservationId), "pg-token", changeStamps.stamp()));
    }

    private void pay(Long reservationId) {
        checkout(reservationId);
        assertThat(startPayment(reservationId)).isEqualTo(1);
    }

    private static String orderId(Long reservationId) {
        return "order-" + reservationId;
    }

    private int countActiveSeats(Long scheduleId) {
        return transactionTemplate.execute(status -> bookingFacade.countActiveSeats(userId, scheduleId));
    }

    private Optional<ReservationView> findActiveHold(Long scheduleId) {
        return transactionTemplate.execute(status -> bookingFacade.findActiveHold(userId, scheduleId));
    }

    private boolean expireIfDue(Long reservationId) {
        return transactionTemplate.execute(status -> bookingFacade.expireIfDue(reservationId));
    }

    private Set<Long> findPaymentInProgress(Collection<Long> reservationIds) {
        return transactionTemplate.execute(status -> bookingFacade.findPaymentInProgress(reservationIds));
    }

    private String statusOf(Long reservationId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM p_reservations WHERE reservation_id = ?", String.class, reservationId);
    }

    @Test
    @DisplayName("countActiveSeats는 이 회원·회차의 HOLD·결제 중·확정 예약의 좌석 수를 센다. 끝난 예약과 다른 회원·회차는 세지 않는다")
    void countActiveSeats() {
        Long scheduleId = TestIds.next();
        Long reservationId = hold(userId, scheduleId, Duration.ofMinutes(10), TestIds.next(), TestIds.next());
        hold(TestIds.next(), scheduleId, Duration.ofMinutes(10), TestIds.next());
        hold(userId, TestIds.next(), Duration.ofMinutes(10), TestIds.next());

        assertThat(countActiveSeats(scheduleId)).as("HOLD").isEqualTo(2);
        pay(reservationId);
        assertThat(countActiveSeats(scheduleId)).as("결제 중").isEqualTo(2);
        transactionTemplate.execute(
                status -> reservationRepository.confirm(reservationId, orderId(reservationId), changeStamps.stamp()));
        assertThat(countActiveSeats(scheduleId)).as("확정").isEqualTo(2);
        // 취소 전이는 아직 없다. 상태만 바꿔 끝난 예약으로 만든다
        jdbcTemplate.update("UPDATE p_reservations SET status = 'CANCELED' WHERE reservation_id = ?", reservationId);
        assertThat(countActiveSeats(scheduleId)).as("취소").isZero();

        Long released = hold(userId, scheduleId, Duration.ofMinutes(10), TestIds.next());
        transactionTemplate.execute(status -> bookingFacade.release(userId, released));
        assertThat(countActiveSeats(scheduleId)).as("해제").isZero();

        Long expired = hold(userId, scheduleId, Duration.ofMinutes(1), TestIds.next());
        clock.advance(Duration.ofMinutes(2));
        assertThat(countActiveSeats(scheduleId))
                .as("시간이 지났어도 아직 HOLD면 센다. 만료 정리는 seat가 expireIfDue로 먼저 한다")
                .isEqualTo(1);
        expireIfDue(expired);
        assertThat(countActiveSeats(scheduleId)).as("만료").isZero();
    }

    @Test
    @DisplayName("findActiveHold는 이 회원·회차의 HOLD를 만료 여부와 상관없이 좌석 id 오름차순으로 돌려준다. 결제 중이면 빈 값이다")
    void findActiveHold() {
        Long scheduleId = TestIds.next();
        Long seatA = TestIds.next();
        Long seatB = TestIds.next();
        assertThat(findActiveHold(scheduleId)).isEmpty();

        LocalDateTime holdExpiresAt = clock.now().plusMinutes(1);
        Long reservationId = holdUntil(userId, scheduleId, holdExpiresAt, seatB, seatA);
        hold(TestIds.next(), scheduleId, Duration.ofMinutes(10), TestIds.next());
        clock.advance(Duration.ofMinutes(5));

        ReservationView view = findActiveHold(scheduleId).orElseThrow();
        assertThat(view.reservationId()).isEqualTo(reservationId);
        assertThat(view.userId()).isEqualTo(userId);
        assertThat(view.scheduleId()).isEqualTo(scheduleId);
        assertThat(view.state()).isEqualTo(ReservationState.HOLD);
        assertThat(view.holdExpiresAt()).as("만료된 HOLD도 돌려준다").isEqualTo(holdExpiresAt);
        assertThat(view.scheduleSeatIds()).containsExactly(seatA, seatB);
        assertThat(findActiveHold(TestIds.next())).as("다른 회차").isEmpty();

        Long payingSchedule = TestIds.next();
        pay(hold(userId, payingSchedule, Duration.ofMinutes(10), TestIds.next()));
        assertThat(findActiveHold(payingSchedule)).as("결제 중").isEmpty();
    }

    @Test
    @DisplayName("expireIfDue는 시간이 지난 HOLD만 EXPIRED로 바꾸고 true다. 만료 전·결제 중·이미 끝난·없는 예약은 바꾸지 않고 false다")
    void expireIfDue() {
        Long due = hold(userId, TestIds.next(), Duration.ofMinutes(1), TestIds.next());
        Long alive = hold(userId, TestIds.next(), Duration.ofMinutes(30), TestIds.next());
        Long paying = hold(userId, TestIds.next(), Duration.ofMinutes(1), TestIds.next());
        pay(paying);
        clock.advance(Duration.ofMinutes(5));

        assertThat(expireIfDue(due)).isTrue();
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, updated_at, updated_by FROM p_reservations WHERE reservation_id = ?", due);
        assertThat(row.get("status")).isEqualTo("EXPIRED");
        assertThat(((Timestamp) row.get("updated_at")).toLocalDateTime())
                .as("DB now()가 아니라 주입한 Clock의 시각")
                .isEqualTo(clock.now());
        assertThat(row.get("updated_by")).isEqualTo("SYSTEM");

        assertThat(expireIfDue(due)).as("이미 EXPIRED").isFalse();
        assertThat(expireIfDue(alive)).as("만료 전").isFalse();
        assertThat(statusOf(alive)).isEqualTo("HOLD");
        assertThat(expireIfDue(paying)).as("결제 중이면 선점 시간이 지났어도 결제 결과를 기다린다").isFalse();
        assertThat(statusOf(paying)).isEqualTo("PAYMENT_IN_PROGRESS");
        assertThat(expireIfDue(TestIds.next())).as("없는 예약").isFalse();
    }

    @Test
    @DisplayName("만료 시각 1초 전에는 결제 시작만, 정각에는 만료만 성공한다. 같은 Clock으로 판정해 경계에서 겹치지 않는다")
    void expiryAndPaymentStartDoNotOverlap() {
        LocalDateTime holdExpiresAt = clock.now().plusMinutes(10);
        Long beforeExpiry = holdUntil(userId, TestIds.next(), holdExpiresAt, TestIds.next());
        Long atExpiry = holdUntil(userId, TestIds.next(), holdExpiresAt, TestIds.next());
        checkout(beforeExpiry);
        checkout(atExpiry);

        clock.set(holdExpiresAt.minusSeconds(1));
        assertThat(expireIfDue(beforeExpiry)).isFalse();
        assertThat(startPayment(beforeExpiry)).isEqualTo(1);

        clock.set(holdExpiresAt);
        assertThat(startPayment(atExpiry)).isZero();
        assertThat(expireIfDue(atExpiry)).isTrue();
        assertThat(statusOf(beforeExpiry)).isEqualTo("PAYMENT_IN_PROGRESS");
        assertThat(statusOf(atExpiry)).isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("seat 선점처럼 한 트랜잭션에서 본인의 만료된 HOLD를 정리하고 다시 선점한다. 먼저 읽어 둔 예약이 flush 때 되돌아가지 않는다")
    void expireAndHoldAgainInOneTransaction() {
        Long scheduleId = TestIds.next();
        Long expired = hold(userId, scheduleId, Duration.ofMinutes(1), TestIds.next());
        clock.advance(Duration.ofMinutes(5));

        Long renewed = transactionTemplate.execute(status -> {
            ReservationView stale =
                    bookingFacade.findActiveHold(userId, scheduleId).orElseThrow();
            assertThat(bookingFacade.expireIfDue(stale.reservationId())).isTrue();
            assertThat(bookingFacade.findActiveHold(userId, scheduleId))
                    .as("같은 트랜잭션에서 바꾼 상태를 쿼리가 본다")
                    .isEmpty();
            assertThat(bookingFacade.countActiveSeats(userId, scheduleId)).isZero();
            return bookingFacade.hold(
                    userId,
                    scheduleId,
                    TestSeats.priced(TestIds.next()),
                    clock.now().plusMinutes(10));
        });

        assertThat(statusOf(expired)).isEqualTo("EXPIRED");
        assertThat(findActiveHold(scheduleId).orElseThrow().reservationId()).isEqualTo(renewed);
    }

    @Test
    @DisplayName("findPaymentInProgress는 넘긴 예약 중 결제 중인 것의 id만 돌려준다. 빈 목록이면 빈 집합이다")
    void findPaymentInProgress() {
        Long paying = hold(userId, TestIds.next(), Duration.ofMinutes(10), TestIds.next());
        Long holding = hold(userId, TestIds.next(), Duration.ofMinutes(10), TestIds.next());
        Long notAsked = hold(userId, TestIds.next(), Duration.ofMinutes(10), TestIds.next());
        pay(paying);
        pay(notAsked);

        assertThat(findPaymentInProgress(List.of(paying, holding, TestIds.next())))
                .containsExactly(paying);
        assertThat(findPaymentInProgress(List.of())).isEmpty();
    }

    @Test
    @DisplayName("네 메서드 모두 트랜잭션 밖에서 부르면 IllegalTransactionStateException이다")
    void requiresTransaction() {
        Long id = TestIds.next();

        assertThatThrownBy(() -> bookingFacade.countActiveSeats(userId, id))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> bookingFacade.findActiveHold(userId, id))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> bookingFacade.expireIfDue(id)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> bookingFacade.findPaymentInProgress(List.of()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
