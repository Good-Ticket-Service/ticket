package com.sparta.ticket.reservation.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.reservation.application.BookingFacade;
import com.sparta.ticket.reservation.application.ChangeStamps;
import com.sparta.ticket.reservation.domain.ReservationRepository;
import com.sparta.ticket.reservation.domain.ReservationStatus;
import com.sparta.ticket.reservation.support.MutableClock;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
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
        jdbcTemplate.update("DELETE FROM p_reservation_seats");
        jdbcTemplate.update("DELETE FROM p_reservations");
        clock.set(LocalDateTime.of(2026, 11, 1, 20, 0));
    }

    private UUID hold(Duration holdFor, UUID... seatIds) {
        return transactionTemplate.execute(status -> bookingFacade.hold(
                userId, UUID.randomUUID(), List.of(seatIds), clock.now().plus(holdFor)));
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

    private String statusOf(UUID reservationId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM p_reservations WHERE reservation_id = ?", String.class, reservationId);
    }

    @Test
    @DisplayName("선점 트랜잭션 안에서 HOLD 예약과 좌석이 저장되고, 트랜잭션 없이 부르면 막는다")
    void hold() {
        UUID seatA = UUID.randomUUID();
        UUID seatB = UUID.randomUUID();

        UUID reservationId = hold(Duration.ofMinutes(10), seatA, seatB);

        assertThat(statusOf(reservationId)).isEqualTo("HOLD");
        assertThat(jdbcTemplate.queryForList(
                        "SELECT seat_id FROM p_reservation_seats WHERE reservation_id = ?", UUID.class, reservationId))
                .containsExactlyInAnyOrder(seatA, seatB);
        assertThatThrownBy(() -> bookingFacade.hold(
                        userId, UUID.randomUUID(), List.of(seatA), clock.now().plusMinutes(10)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("여러 트랜잭션이 모두 열린 뒤 같은 전이를 동시에 시도해도 한쪽만 1행이다")
    void concurrentTransition() throws Exception {
        UUID reservationId = hold(Duration.ofMinutes(10), UUID.randomUUID());
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
        UUID reservationId = hold(Duration.ofMinutes(10), UUID.randomUUID());
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
        UUID reservationId = hold(Duration.ofMinutes(10), UUID.randomUUID());

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
        UUID reservationId = hold(Duration.ofMinutes(10), UUID.randomUUID());
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
        UUID expired = hold(Duration.ofMinutes(1), UUID.randomUUID());
        UUID alive = hold(Duration.ofMinutes(30), UUID.randomUUID());
        UUID paying = hold(Duration.ofMinutes(1), UUID.randomUUID());
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
    @DisplayName("테이블은 backend/sql/reservation-service/schema.sql로 만들어진다(이름 붙인 제약이 있다)")
    void schemaFromSqlFile() {
        assertThat(jdbcTemplate.queryForList(
                        "SELECT conname FROM pg_constraint WHERE conrelid = 'p_reservations'::regclass", String.class))
                .contains("uk_p_reservations_order_id", "ck_p_reservations_status");
    }
}
