package com.sparta.ticket.reservation.booking.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.reservation.booking.application.ChangeStamps;
import com.sparta.ticket.reservation.booking.application.facade.BookingFacade;
import com.sparta.ticket.reservation.booking.application.facade.HoldTarget;
import com.sparta.ticket.reservation.booking.application.facade.ReleaseResult;
import com.sparta.ticket.reservation.booking.application.facade.ReservationState;
import com.sparta.ticket.reservation.booking.application.facade.ReservationView;
import com.sparta.ticket.reservation.booking.domain.ReservationRepository;
import com.sparta.ticket.reservation.booking.domain.ReservationStatus;
import com.sparta.ticket.reservation.booking.support.MutableClock;
import com.sparta.ticket.reservation.booking.support.TestHoldTargets;
import com.sparta.ticket.reservation.booking.support.TestIds;
import com.sparta.ticket.reservation.booking.support.TestSeats;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

class ReservationTransitionIntegrationTest extends ReservationIntegrationTest {

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

    private Long hold(Duration holdFor, Long... seatIds) {
        return transactionTemplate.execute(status -> bookingFacade.hold(
                userId,
                TestHoldTargets.of(TestIds.next()),
                TestSeats.priced(seatIds),
                clock.now().plus(holdFor)));
    }

    private int inTransaction(Callable<Integer> action) {
        return transactionTemplate.execute(status -> {
            try {
                return action.call();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private ReleaseResult release(Long user, Long reservationId) {
        return transactionTemplate.execute(status -> bookingFacade.release(user, reservationId));
    }

    private String statusOf(Long reservationId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM p_reservations WHERE reservation_id = ?", String.class, reservationId);
    }

    @Test
    @DisplayName("선점 트랜잭션 안에서 HOLD 예약과 좌석마다 티켓이 저장되고, 트랜잭션 없이 부르거나 같은 좌석을 두 번 넣거나 공연·판매자가 없으면 막는다")
    void hold() {
        Long seatA = TestIds.next();
        Long seatB = TestIds.next();

        Long reservationId = hold(Duration.ofMinutes(10), seatA, seatB);

        assertThat(statusOf(reservationId)).isEqualTo("HOLD");
        assertThat(jdbcTemplate.queryForList(
                        "SELECT schedule_seat_id, price FROM p_tickets WHERE reservation_id = ? ORDER BY schedule_seat_id",
                        reservationId))
                .containsExactly(
                        Map.of("schedule_seat_id", seatA, "price", TestSeats.PRICE),
                        Map.of("schedule_seat_id", seatB, "price", TestSeats.PRICE));
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM p_tickets WHERE reservation_id = ? AND ticket_code IS NULL",
                        Integer.class,
                        reservationId))
                .as("티켓 코드는 확정 때 발급한다")
                .isEqualTo(2);
        assertThatThrownBy(() -> bookingFacade.hold(
                        userId,
                        TestHoldTargets.of(TestIds.next()),
                        TestSeats.priced(seatA),
                        clock.now().plusMinutes(10)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> hold(Duration.ofMinutes(10), seatA, seatA))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("같은 좌석");
        assertThatThrownBy(() -> transactionTemplate.execute(status -> bookingFacade.hold(
                        userId,
                        new HoldTarget(TestIds.next(), null, TestHoldTargets.SELLER_ID),
                        TestSeats.priced(TestIds.next()),
                        clock.now().plusMinutes(10))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("판매자");
    }

    @Test
    @DisplayName("같은 회원이 같은 회차에 진행 중인 예약을 또 만들면 부분 UNIQUE 위반이 hold 호출 안에서 난다. 끝난 예약은 막지 않는다")
    void holdRejectsSecondActiveReservationInsideCall() {
        Long scheduleId = TestIds.next();
        Long first = transactionTemplate.execute(status -> bookingFacade.hold(
                userId,
                TestHoldTargets.of(scheduleId),
                TestSeats.priced(TestIds.next()),
                clock.now().plusMinutes(10)));

        // 커밋 때가 아니라 호출 안에서 나야 seat가 좌석을 바꾸기 전에 실패한다. 409 변환은 seat가 트랜잭션 밖에서 한다
        transactionTemplate.executeWithoutResult(status -> {
            assertThatThrownBy(() -> bookingFacade.hold(
                            userId,
                            TestHoldTargets.of(scheduleId),
                            TestSeats.priced(TestIds.next()),
                            clock.now().plusMinutes(10)))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("uk_p_reservations_active_user_schedule");
            status.setRollbackOnly();
        });

        release(userId, first);
        Long again = transactionTemplate.execute(status -> bookingFacade.hold(
                userId,
                TestHoldTargets.of(scheduleId),
                TestSeats.priced(TestIds.next()),
                clock.now().plusMinutes(10)));
        assertThat(again).as("RELEASED는 진행 중인 예약이 아니다").isNotNull();
    }

    @Test
    @DisplayName("선점 해제: HOLD는 RELEASED, 시간이 지난 HOLD는 EXPIRED로 정리하고, 다시 오면 ALREADY_CLOSED다. 티켓은 남는다")
    void releaseHold() {
        Long released = hold(Duration.ofMinutes(10), TestIds.next());
        Long stale = hold(Duration.ofMinutes(1), TestIds.next());
        inTransaction(() ->
                reservationRepository.recordCheckout(released, userId, null, "order-r", 10_000L, changeStamps.stamp()));
        clock.advance(Duration.ofMinutes(5));

        assertThat(release(userId, released)).isEqualTo(ReleaseResult.RELEASED);
        assertThat(release(userId, stale)).isEqualTo(ReleaseResult.EXPIRED);
        assertThat(release(userId, released)).isEqualTo(ReleaseResult.ALREADY_CLOSED);
        assertThat(release(userId, stale)).isEqualTo(ReleaseResult.ALREADY_CLOSED);

        assertThat(statusOf(released)).isEqualTo("RELEASED");
        assertThat(statusOf(stale)).as("시간 초과와 사용자 해제를 따로 센다").isEqualTo("EXPIRED");
        assertThat(inTransaction(() -> reservationRepository.startPayment(
                        released, userId, "order-r", "pg-token", changeStamps.stamp())))
                .as("해제한 예약은 준비해 둔 주문으로도 결제를 시작할 수 없다")
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM p_tickets WHERE reservation_id = ?", Integer.class, released))
                .isEqualTo(1);
        assertThatThrownBy(() -> bookingFacade.release(userId, released))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("getReservation은 상태와 상관없이 예약을 읽고, 좌석 id를 잠그는 순서(오름차순)로 돌려준다")
    void getReservationReturnsSeatsInLockOrder() {
        Long seatA = TestIds.next();
        Long seatB = TestIds.next();
        Long reservationId = hold(Duration.ofMinutes(10), seatB, seatA);
        release(userId, reservationId);

        ReservationView view = transactionTemplate.execute(
                status -> bookingFacade.getReservation(reservationId).orElseThrow());

        assertThat(view.state()).isEqualTo(ReservationState.RELEASED);
        assertThat(view.userId()).isEqualTo(userId);
        assertThat(view.scheduleSeatIds()).containsExactly(seatA, seatB);
        Optional<ReservationView> missing =
                transactionTemplate.execute(status -> bookingFacade.getReservation(TestIds.next()));
        assertThat(missing).isEmpty();
    }

    @Test
    @DisplayName("결제 중이거나 끝난 예약은 NOT_RELEASABLE이고 상태를 바꾸지 않는다. 다른 사용자의 예약은 FORBIDDEN이다")
    void releaseRejected() {
        Long paying = hold(Duration.ofMinutes(10), TestIds.next());
        inTransaction(() ->
                reservationRepository.recordCheckout(paying, userId, null, "order-1", 10_000L, changeStamps.stamp()));
        inTransaction(
                () -> reservationRepository.startPayment(paying, userId, "order-1", "pg-token", changeStamps.stamp()));
        Long other = hold(Duration.ofMinutes(10), TestIds.next());

        assertThat(release(userId, paying)).isEqualTo(ReleaseResult.NOT_RELEASABLE);
        assertThat(statusOf(paying)).isEqualTo("PAYMENT_IN_PROGRESS");
        assertThatThrownBy(() -> release(TestIds.next(), other))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.FORBIDDEN));
        assertThat(statusOf(other)).isEqualTo("HOLD");
    }

    @Test
    @DisplayName("여러 트랜잭션이 모두 열린 뒤 같은 전이를 동시에 시도해도 한쪽만 1행이다")
    void concurrentTransition() throws Exception {
        Long reservationId = hold(Duration.ofMinutes(10), TestIds.next());
        inTransaction(() -> reservationRepository.recordCheckout(
                reservationId, userId, null, "order-1", 10_000L, changeStamps.stamp()));

        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CyclicBarrier allInTransaction = new CyclicBarrier(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> inTransaction(() -> {
                allInTransaction.await(5, TimeUnit.SECONDS);
                return reservationRepository.startPayment(
                        reservationId, userId, "order-1", "pg-token", changeStamps.stamp());
            })));
        }
        int updated = 0;
        for (Future<Integer> future : futures) {
            updated += future.get();
        }
        executor.shutdown();

        assertThat(updated).isEqualTo(1);
        assertThat(statusOf(reservationId)).isEqualTo("PAYMENT_IN_PROGRESS");
    }

    @Test
    @DisplayName("선점 해제와 결제 시작이 동시에 와도 한쪽만 성공하고, 상태는 이긴 쪽을 따른다")
    void releaseAndPaymentStartRace() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 20; round++) {
                Long reservationId = hold(Duration.ofMinutes(10), TestIds.next());
                String orderId = "order-race-" + round;
                inTransaction(() -> reservationRepository.recordCheckout(
                        reservationId, userId, null, orderId, 10_000L, changeStamps.stamp()));
                CyclicBarrier bothInTransaction = new CyclicBarrier(2);

                Future<ReleaseResult> released = executor.submit(() -> transactionTemplate.execute(status -> {
                    await(bothInTransaction);
                    return bookingFacade.release(userId, reservationId);
                }));
                Future<Integer> started = executor.submit(() -> inTransaction(() -> {
                    await(bothInTransaction);
                    return reservationRepository.startPayment(
                            reservationId, userId, orderId, "pg-token", changeStamps.stamp());
                }));

                boolean releaseWon = released.get(5, TimeUnit.SECONDS) == ReleaseResult.RELEASED;
                boolean paymentWon = started.get(5, TimeUnit.SECONDS) == 1;
                assertThat(releaseWon ^ paymentWon)
                        .as("round %d: 해제 %s, 결제 시작 %s", round, releaseWon, paymentWon)
                        .isTrue();
                assertThat(statusOf(reservationId)).isEqualTo(releaseWon ? "RELEASED" : "PAYMENT_IN_PROGRESS");
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("조건부 UPDATE가 updated_at을 주입한 시각으로, 요청 밖에서는 updated_by를 SYSTEM으로 남긴다")
    void auditColumns() {
        Long reservationId = hold(Duration.ofMinutes(10), TestIds.next());
        clock.advance(Duration.ofMinutes(11));

        int updated = inTransaction(() -> reservationRepository.expireHold(reservationId, changeStamps.stamp()));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, updated_at, updated_by FROM p_reservations WHERE reservation_id = ?", reservationId);
        assertThat(updated).isEqualTo(1);
        assertThat(row.get("status")).isEqualTo("EXPIRED");
        assertThat(((Timestamp) row.get("updated_at")).toLocalDateTime()).isEqualTo(clock.now());
        assertThat(row.get("updated_by")).isEqualTo("SYSTEM");
    }

    @Test
    @DisplayName("전이 조건이 맞지 않으면 0행이다: 만료 전 만료, 다른 주문의 결과, 처음 읽은 주문과 다른 준비")
    void conditionsGuardTransitions() {
        Long reservationId = hold(Duration.ofMinutes(10), TestIds.next());

        assertThat(inTransaction(() -> reservationRepository.expireHold(reservationId, changeStamps.stamp())))
                .isZero();
        assertThat(inTransaction(() -> reservationRepository.recordCheckout(
                        reservationId, userId, null, "order-1", 10_000L, changeStamps.stamp())))
                .isEqualTo(1);
        assertThat(inTransaction(() -> reservationRepository.recordCheckout(
                        reservationId, userId, null, "order-2", 10_000L, changeStamps.stamp())))
                .as("처음 읽은 order_id(null)가 이미 바뀌었다")
                .isZero();
        assertThat(inTransaction(() -> reservationRepository.startPayment(
                        reservationId, TestIds.next(), "order-1", "pg-token", changeStamps.stamp())))
                .as("본인이 아니다")
                .isZero();
        assertThat(inTransaction(() -> reservationRepository.startPayment(
                        reservationId, userId, "order-1", "pg-token", changeStamps.stamp())))
                .isEqualTo(1);
        assertThat(inTransaction(() -> reservationRepository.confirm(reservationId, "order-0", changeStamps.stamp())))
                .as("지난 시도의 결과다")
                .isZero();
        assertThat(inTransaction(() -> reservationRepository.confirm(reservationId, "order-1", changeStamps.stamp())))
                .isEqualTo(1);
        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(reservationRepository.findById(reservationId).orElseThrow().getPgAuthToken())
                .as("결제가 끝나면 인증값을 지운다")
                .isNull();
    }

    @Test
    @DisplayName("결제 실패는 선점 시간이 남았으면 HOLD, 지났으면 EXPIRED로만 바뀐다")
    void paymentFailureDependsOnHoldTime() {
        Long reservationId = hold(Duration.ofMinutes(10), TestIds.next());
        inTransaction(() -> reservationRepository.recordCheckout(
                reservationId, userId, null, "order-1", 10_000L, changeStamps.stamp()));
        inTransaction(() ->
                reservationRepository.startPayment(reservationId, userId, "order-1", "pg-token", changeStamps.stamp()));

        clock.advance(Duration.ofMinutes(11));

        assertThat(inTransaction(
                        () -> reservationRepository.returnToHold(reservationId, "order-1", changeStamps.stamp())))
                .isZero();
        assertThat(inTransaction(
                        () -> reservationRepository.expireAfterFailure(reservationId, "order-1", changeStamps.stamp())))
                .isEqualTo(1);
        assertThat(statusOf(reservationId)).isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("만료 후보는 시간이 지난 HOLD만 고르고, 결제 중 예약은 고르지 않는다")
    void expiredHoldCandidates() {
        Long expired = hold(Duration.ofMinutes(1), TestIds.next());
        Long alive = hold(Duration.ofMinutes(30), TestIds.next());
        Long paying = hold(Duration.ofMinutes(1), TestIds.next());
        inTransaction(() ->
                reservationRepository.recordCheckout(paying, userId, null, "order-p", 10_000L, changeStamps.stamp()));
        inTransaction(
                () -> reservationRepository.startPayment(paying, userId, "order-p", "pg-token", changeStamps.stamp()));

        clock.advance(Duration.ofMinutes(5));

        assertThat(reservationRepository.findExpiredHoldIds(clock.now(), 10))
                .containsExactly(expired)
                .doesNotContain(alive, paying);
    }

    @Test
    @DisplayName("테이블은 backend/sql/reservation-service/booking.sql로 만들어진다(이름 붙인 제약이 있다)")
    void schemaFromSqlFile() {
        assertThat(jdbcTemplate.queryForList(
                        "SELECT conname FROM pg_constraint WHERE conrelid = 'p_reservations'::regclass", String.class))
                .contains("uk_p_reservations_order_id", "ck_p_reservations_status");
        assertThat(jdbcTemplate.queryForList(
                        "SELECT indexname FROM pg_indexes WHERE tablename = 'p_reservations'", String.class))
                .contains("uk_p_reservations_active_user_schedule");
    }
}
