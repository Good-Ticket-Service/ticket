package com.sparta.ticket.reservation.booking.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.reservation.booking.application.BookingFacade;
import com.sparta.ticket.reservation.booking.application.ChangeStamps;
import com.sparta.ticket.reservation.booking.domain.ReservationRepository;
import com.sparta.ticket.reservation.booking.domain.ReservationStatus;
import com.sparta.ticket.reservation.booking.support.MutableClock;
import com.sparta.ticket.reservation.booking.support.ReservationIntegrationTest;
import com.sparta.ticket.reservation.booking.support.TestIds;
import com.sparta.ticket.reservation.booking.support.TestSeats;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM p_tickets");
        jdbcTemplate.update("DELETE FROM p_reservations");
        clock.set(LocalDateTime.of(2026, 11, 1, 20, 0));
    }

    private Long hold(Duration holdFor, Long... seatIds) {
        return transactionTemplate.execute(status -> bookingFacade.hold(
                userId, TestIds.next(), TestSeats.priced(seatIds), clock.now().plus(holdFor)));
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

    private void release(UUID user, Long reservationId) {
        transactionTemplate.executeWithoutResult(status -> bookingFacade.release(user, reservationId));
    }

    private String statusOf(Long reservationId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM p_reservations WHERE reservation_id = ?", String.class, reservationId);
    }

    @Test
    @DisplayName("선점 트랜잭션 안에서 HOLD 예약과 좌석마다 티켓이 저장되고, 트랜잭션 없이 부르거나 같은 좌석을 두 번 넣으면 막는다")
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
                        TestIds.next(),
                        TestSeats.priced(seatA),
                        clock.now().plusMinutes(10)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> hold(Duration.ofMinutes(10), seatA, seatA))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("같은 좌석");
    }

    @Test
    @DisplayName("선점 해제: HOLD는 RELEASED, 시간이 지난 HOLD는 EXPIRED로 정리한다. 다시 와도 정상으로 끝나고 티켓은 남는다")
    void releaseHold() {
        Long released = hold(Duration.ofMinutes(10), TestIds.next());
        Long stale = hold(Duration.ofMinutes(1), TestIds.next());
        inTransaction(() ->
                reservationRepository.recordCheckout(released, userId, null, "order-r", 10_000L, changeStamps.stamp()));
        clock.advance(Duration.ofMinutes(5));

        release(userId, released);
        release(userId, stale);
        release(userId, released);

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
    @DisplayName("결제 중이거나 끝난 예약, 다른 사용자의 예약은 선점을 해제할 수 없다")
    void releaseRejected() {
        Long paying = hold(Duration.ofMinutes(10), TestIds.next());
        inTransaction(() ->
                reservationRepository.recordCheckout(paying, userId, null, "order-1", 10_000L, changeStamps.stamp()));
        inTransaction(
                () -> reservationRepository.startPayment(paying, userId, "order-1", "pg-token", changeStamps.stamp()));
        Long other = hold(Duration.ofMinutes(10), TestIds.next());

        assertThatThrownBy(() -> release(userId, paying))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_STATUS_TRANSITION));
        assertThat(statusOf(paying)).isEqualTo("PAYMENT_IN_PROGRESS");
        assertThatThrownBy(() -> release(UUID.randomUUID(), other))
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
                        reservationId, UUID.randomUUID(), "order-1", "pg-token", changeStamps.stamp())))
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
    }
}
