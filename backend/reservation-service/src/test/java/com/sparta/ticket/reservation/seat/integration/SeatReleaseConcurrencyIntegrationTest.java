package com.sparta.ticket.reservation.seat.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.booking.support.MutableClock;
import com.sparta.ticket.reservation.seat.application.hold.SeatHoldService;
import com.sparta.ticket.reservation.seat.application.release.SeatReleaseService;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeat;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeatRepository;
import com.sparta.ticket.reservation.seat.support.FakeSaleValidationPort;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 선점 해제가 다른 작업과 동시에 올 때 좌석과 예약이 어긋나지 않는지 실제 트랜잭션 두 개로 확인한다.
 * 모든 경로가 좌석(id 오름차순) → 예약 순서로 잠그므로 교착하지 않고, 각 테스트는 시간 제한 안에 끝나야 한다.
 * 순서는 우연에 맡기지 않는다. 예약 행을 잡은 트랜잭션으로 먼저 온 작업을 예약 UPDATE에서 멈추고, 나중 작업이 좌석 잠금을 기다리는
 * 것을 pg_stat_activity로 확인한 뒤 풀어 준다.
 */
class SeatReleaseConcurrencyIntegrationTest extends ReservationIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 20, 0);
    private static final Long SCHEDULE = 901L;
    private static final Long USER = 1L;
    private static final Long OTHER_USER = 2L;

    @Autowired
    private SeatReleaseService seatReleaseService;

    @Autowired
    private SeatHoldService seatHoldService;

    @Autowired
    private ScheduleSeatRepository scheduleSeatRepository;

    @Autowired
    private FakeSaleValidationPort saleValidation;

    @Autowired
    private MutableClock clock;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ExecutorService pool = Executors.newFixedThreadPool(3);

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM p_schedule_seats");
        jdbcTemplate.update("DELETE FROM p_tickets");
        jdbcTemplate.update("DELETE FROM p_reservations");
        saleValidation.reset();
        clock.set(NOW);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private Long seat() {
        return scheduleSeatRepository
                .save(ScheduleSeat.create(
                        SCHEDULE, 1L, 7L, "VIP", FakeSaleValidationPort.DEFAULT_PRICE, "A", "1", 1, true, 2L))
                .getId();
    }

    private Map<String, Object> seatRow(Long seatId) {
        return jdbcTemplate.queryForMap("SELECT status, reservation_id FROM p_schedule_seats WHERE id = ?", seatId);
    }

    private String reservationStatus(Long reservationId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM p_reservations WHERE reservation_id = ?", String.class, reservationId);
    }

    /** 작업 결과를 "OK" 또는 오류 code로 돌려준다. */
    private static String outcome(Callable<?> task) throws Exception {
        try {
            task.call();
            return "OK";
        } catch (BusinessException e) {
            return e.getErrorCode().code();
        }
    }

    /**
     * 예약 행을 FOR UPDATE로 잡고 commit 신호까지 기다리는 트랜잭션을 연다. 예약 UPDATE를 막아 두 작업의 순서를 정하는 데 쓴다.
     * 반환 시점에는 이미 예약 행을 잡고 있다.
     */
    private Future<?> lockReservation(Long reservationId, CountDownLatch commit) {
        CountDownLatch locked = new CountDownLatch(1);
        Future<?> holder = pool.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.queryForObject(
                    "SELECT reservation_id FROM p_reservations WHERE reservation_id = ? FOR UPDATE",
                    Long.class,
                    reservationId);
            locked.countDown();
            await(commit);
        }));
        await(locked);
        return holder;
    }

    /**
     * 만료 스케줄러의 한 건 처리(HoldExpiryTransactions.expireHold → closeAndRelease)를 같은 순서의 SQL로 재현한다.
     * 테스트 공용 컨텍스트는 booking의 SeatPort를 가짜로 바꿔 두어 실제 스케줄러로는 좌석을 잠그지 않기 때문이다.
     * 좌석 잠금 → 예약 조건부 만료 → 성공하면 이 예약이 가진 좌석만 반환. 만료했으면 true다.
     */
    private boolean expireLikeScheduler(Long reservationId, Long seatId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            jdbcTemplate.queryForList("SELECT id FROM p_schedule_seats WHERE id = ? FOR UPDATE", seatId);
            int expired = jdbcTemplate.update(
                    "UPDATE p_reservations SET status = 'EXPIRED' WHERE reservation_id = ? AND status = 'HOLD'"
                            + " AND hold_expires_at <= ?",
                    reservationId,
                    LocalDateTime.now(clock));
            if (expired == 0) {
                return false;
            }
            jdbcTemplate.update(
                    "UPDATE p_schedule_seats SET status = 'AVAILABLE', reservation_id = NULL, hold_expires_at = NULL"
                            + " WHERE id = ? AND reservation_id = ?",
                    seatId,
                    reservationId);
            return true;
        }));
    }

    private Future<String> submitRelease(Long reservationId) {
        return pool.submit(() -> outcome(() -> {
            seatReleaseService.release(USER, SCHEDULE, reservationId);
            return null;
        }));
    }

    @Test
    @DisplayName("결제 시작이 예약을 먼저 잡으면, 해제는 좌석을 잠근 채 기다렸다가 409이고 좌석은 결제 중 예약의 HOLD로 남는다")
    void paymentStartWinsOverRelease() throws Exception {
        Long seatId = seat();
        Long reservationId = seatHoldService.hold(USER, SCHEDULE, seatId).reservationId();
        CountDownLatch paymentUpdated = new CountDownLatch(1);
        CountDownLatch commitPayment = new CountDownLatch(1);

        // ① 결제 시작 역할: 예약을 HOLD → PAYMENT_IN_PROGRESS로 조건부 변경하고 커밋 전에 기다린다(예약 행 잠금을 쥔 상태)
        Future<?> payment = pool.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update(
                    "UPDATE p_reservations SET status = 'PAYMENT_IN_PROGRESS' WHERE reservation_id = ? AND status = 'HOLD'",
                    reservationId);
            paymentUpdated.countDown();
            await(commitPayment);
        }));
        await(paymentUpdated);

        // ② 해제: 좌석을 잠근 뒤 booking의 release가 예약 UPDATE에서 ①을 기다린다
        Future<String> release = submitRelease(reservationId);
        waitUntilLockWaiters("p_reservations", 1);
        commitPayment.countDown();
        payment.get(30, TimeUnit.SECONDS);

        // ①이 커밋하면 release의 HOLD 조건부 UPDATE는 0행이고, 결제 중이라 NOT_RELEASABLE → 409
        assertThat(release.get(30, TimeUnit.SECONDS)).isEqualTo("INVALID_STATUS_TRANSITION");
        assertThat(reservationStatus(reservationId)).isEqualTo("PAYMENT_IN_PROGRESS");
        assertThat(seatRow(seatId)).containsEntry("status", "HOLD");
        assertThat(((Number) seatRow(seatId).get("reservation_id")).longValue()).isEqualTo(reservationId);
    }

    @Test
    @DisplayName("만료된 선점의 해제가 좌석을 먼저 잠그면, 같은 좌석을 노린 다른 회원의 선점은 기다렸다가 반환된 좌석을 잡는다")
    void expiredReleaseFirstThenOthersHold() throws Exception {
        Long seatId = seat();
        Long reservationId = seatHoldService.hold(USER, SCHEDULE, seatId).reservationId();
        clock.advance(Duration.ofMinutes(11));
        CountDownLatch commit = new CountDownLatch(1);
        Future<?> holder = lockReservation(reservationId, commit);

        // 해제가 좌석을 잠그고 예약 UPDATE에서 멈춘 뒤, 선점이 같은 좌석 잠금을 기다린다
        Future<String> release = submitRelease(reservationId);
        waitUntilLockWaiters("p_reservations", 1);
        Future<String> hold = pool.submit(() -> outcome(() -> seatHoldService.hold(OTHER_USER, SCHEDULE, seatId)));
        waitUntilLockWaiters("p_schedule_seats", 1);
        commit.countDown();
        holder.get(30, TimeUnit.SECONDS);

        assertThat(release.get(30, TimeUnit.SECONDS)).isEqualTo("OK");
        assertThat(hold.get(30, TimeUnit.SECONDS)).isEqualTo("OK");
        assertThat(reservationStatus(reservationId)).isEqualTo("EXPIRED");
        assertHeldByActiveHoldOf(seatId, OTHER_USER);
    }

    @Test
    @DisplayName("다른 회원의 선점이 만료된 선점을 먼저 정리하고 좌석을 잡으면, 해제는 ALREADY_CLOSED로 204이고 그 좌석은 건드리지 않는다")
    void othersHoldFirstThenExpiredRelease() throws Exception {
        Long seatId = seat();
        Long reservationId = seatHoldService.hold(USER, SCHEDULE, seatId).reservationId();
        clock.advance(Duration.ofMinutes(11));
        CountDownLatch commit = new CountDownLatch(1);
        Future<?> holder = lockReservation(reservationId, commit);

        // 선점이 좌석을 잠그고 expireIfDue의 예약 UPDATE에서 멈춘 뒤, 해제가 같은 좌석 잠금을 기다린다.
        // 해제는 예약을 HOLD로 읽은 채 기다리므로, 잠근 뒤에는 DB에서 다시 판단해야 한다(booking release의 reload)
        Future<String> hold = pool.submit(() -> outcome(() -> seatHoldService.hold(OTHER_USER, SCHEDULE, seatId)));
        waitUntilLockWaiters("p_reservations", 1);
        Future<String> release = submitRelease(reservationId);
        waitUntilLockWaiters("p_schedule_seats", 1);
        commit.countDown();
        holder.get(30, TimeUnit.SECONDS);

        assertThat(hold.get(30, TimeUnit.SECONDS)).isEqualTo("OK");
        assertThat(release.get(30, TimeUnit.SECONDS)).isEqualTo("OK");
        assertThat(reservationStatus(reservationId)).isEqualTo("EXPIRED");
        assertHeldByActiveHoldOf(seatId, OTHER_USER);
    }

    @Test
    @DisplayName("해제가 먼저 좌석을 잠그면 만료 스케줄러는 기다렸다가 아무것도 바꾸지 않는다")
    void releaseFirstThenExpiryScheduler() throws Exception {
        Long seatId = seat();
        Long reservationId = seatHoldService.hold(USER, SCHEDULE, seatId).reservationId();
        clock.advance(Duration.ofMinutes(11));
        CountDownLatch commit = new CountDownLatch(1);
        Future<?> holder = lockReservation(reservationId, commit);

        Future<String> release = submitRelease(reservationId);
        waitUntilLockWaiters("p_reservations", 1);
        Future<Boolean> expiry = pool.submit(() -> expireLikeScheduler(reservationId, seatId));
        waitUntilLockWaiters("p_schedule_seats", 1);
        commit.countDown();
        holder.get(30, TimeUnit.SECONDS);

        assertThat(release.get(30, TimeUnit.SECONDS)).isEqualTo("OK");
        assertThat(expiry.get(30, TimeUnit.SECONDS)).isFalse();
        assertThat(reservationStatus(reservationId)).isEqualTo("EXPIRED");
        assertThat(seatRow(seatId)).containsEntry("status", "AVAILABLE").containsEntry("reservation_id", null);
    }

    @Test
    @DisplayName("만료 스케줄러가 먼저 좌석을 잠그면 해제는 기다렸다가 ALREADY_CLOSED로 204이다")
    void expirySchedulerFirstThenRelease() throws Exception {
        Long seatId = seat();
        Long reservationId = seatHoldService.hold(USER, SCHEDULE, seatId).reservationId();
        clock.advance(Duration.ofMinutes(11));
        CountDownLatch commit = new CountDownLatch(1);
        Future<?> holder = lockReservation(reservationId, commit);

        Future<Boolean> expiry = pool.submit(() -> expireLikeScheduler(reservationId, seatId));
        waitUntilLockWaiters("p_reservations", 1);
        Future<String> release = submitRelease(reservationId);
        waitUntilLockWaiters("p_schedule_seats", 1);
        commit.countDown();
        holder.get(30, TimeUnit.SECONDS);

        assertThat(expiry.get(30, TimeUnit.SECONDS)).isTrue();
        assertThat(release.get(30, TimeUnit.SECONDS)).isEqualTo("OK");
        assertThat(reservationStatus(reservationId)).isEqualTo("EXPIRED");
        assertThat(seatRow(seatId)).containsEntry("status", "AVAILABLE").containsEntry("reservation_id", null);
    }

    @Test
    @DisplayName("같은 해제가 동시에 두 번 오면 먼저 잠근 쪽이 RELEASED로 바꾸고, 나중 쪽은 ALREADY_CLOSED로 둘 다 204이다")
    void doubleRelease() throws Exception {
        Long seatId = seat();
        Long reservationId = seatHoldService.hold(USER, SCHEDULE, seatId).reservationId();
        CountDownLatch commit = new CountDownLatch(1);
        Future<?> holder = lockReservation(reservationId, commit);

        Future<String> first = submitRelease(reservationId);
        waitUntilLockWaiters("p_reservations", 1);
        Future<String> second = submitRelease(reservationId);
        waitUntilLockWaiters("p_schedule_seats", 1);
        commit.countDown();
        holder.get(30, TimeUnit.SECONDS);

        assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo("OK");
        assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo("OK");
        assertThat(reservationStatus(reservationId)).isEqualTo("RELEASED");
        assertThat(seatRow(seatId)).containsEntry("status", "AVAILABLE").containsEntry("reservation_id", null);
    }

    /** 좌석이 그 회원의 HOLD 예약에 잡혀 있다. */
    private void assertHeldByActiveHoldOf(Long seatId, Long userId) {
        Long holdId = jdbcTemplate.queryForObject(
                "SELECT reservation_id FROM p_reservations WHERE user_id = ? AND status = 'HOLD'", Long.class, userId);
        Map<String, Object> seat = seatRow(seatId);
        assertThat(seat).containsEntry("status", "HOLD");
        assertThat(((Number) seat.get("reservation_id")).longValue()).isEqualTo(holdId);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("기다리는 신호가 오지 않았다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * 그 테이블을 다루는 문장에서 행 잠금을 기다리는 연결이 count개 이상이 될 때까지 기다린다. 테이블로 나눠 보아야
     * "예약 UPDATE에서 멈췄다"와 "좌석 잠금에서 멈췄다"를 구분해 순서를 정할 수 있다.
     */
    private void waitUntilLockWaiters(String table, int count) throws InterruptedException {
        for (int i = 0; i < 300; i++) {
            Integer waiting = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()"
                            + " AND query ILIKE ?",
                    Integer.class,
                    "%" + table + "%");
            if (waiting != null && waiting >= count) {
                return;
            }
            Thread.sleep(20);
        }
        throw new IllegalStateException(table + " 잠금을 기다리는 요청이 " + count + "개 보이지 않는다");
    }
}
