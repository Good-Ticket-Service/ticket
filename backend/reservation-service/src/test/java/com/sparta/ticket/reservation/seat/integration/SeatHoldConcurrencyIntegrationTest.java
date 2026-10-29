package com.sparta.ticket.reservation.seat.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.booking.application.facade.BookingFacade;
import com.sparta.ticket.reservation.booking.support.MutableClock;
import com.sparta.ticket.reservation.booking.support.TestHoldTargets;
import com.sparta.ticket.reservation.booking.support.TestSeats;
import com.sparta.ticket.reservation.seat.application.hold.SeatHoldResult;
import com.sparta.ticket.reservation.seat.application.hold.SeatHoldService;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeat;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeatRepository;
import com.sparta.ticket.reservation.seat.support.FakeSaleValidationPort;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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
 * 좌석 선점을 두 요청이 동시에 보낼 때 DB가 한 건만 받아들이는지 실제 트랜잭션 두 개로 확인한다.
 *
 * - 같은 좌석: 좌석 행 잠금(FOR UPDATE)으로 줄을 세운다. 뒤의 요청은 잠금을 기다린 뒤 앞의 HOLD를 보고 409 SEAT_ALREADY_TAKEN이다.
 * - 같은 회원의 다른 좌석: 좌석이 달라 서로 기다리지 않고 둘 다 매수 제한(countActiveSeats=0)을 통과할 수 있다. 마지막 방어는 예약의
 *   부분 UNIQUE이고, 뒤에 INSERT한 쪽이 위반 → 트랜잭션 밖에서 409 PURCHASE_LIMIT_EXCEEDED로 바뀐다.
 *
 * 두 요청을 래치로 동시에 출발시키되, 어느 쪽이 먼저 잠금을 잡든 결과(성공 1건, 409 1건)는 같아야 한다.
 */
class SeatHoldConcurrencyIntegrationTest extends ReservationIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 20, 0);
    private static final Long SCHEDULE = 701L;

    @Autowired
    private SeatHoldService seatHoldService;

    @Autowired
    private ScheduleSeatRepository scheduleSeatRepository;

    @Autowired
    private FakeSaleValidationPort saleValidation;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BookingFacade bookingFacade;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private long nextVenueSeatId = 1;

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
        long venueSeatId = nextVenueSeatId++;
        return scheduleSeatRepository
                .save(ScheduleSeat.create(
                        SCHEDULE,
                        venueSeatId,
                        7L,
                        "VIP",
                        FakeSaleValidationPort.DEFAULT_PRICE,
                        "A",
                        "1",
                        (int) venueSeatId,
                        true,
                        2L))
                .getId();
    }

    /** 두 선점을 동시에 출발시키고 각 결과를 "CREATED"(201)·"EXISTING"(200) 또는 오류 code로 돌려준다. */
    private List<String> runTogether(Callable<?> first, Callable<?> second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (Callable<?> task : List.of(first, second)) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    SeatHoldResult result = (SeatHoldResult) task.call();
                    return result.created() ? "CREATED" : "EXISTING";
                } catch (BusinessException e) {
                    return e.getErrorCode().code();
                }
            }));
        }
        start.countDown();
        List<String> results = new ArrayList<>();
        for (Future<String> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        return results;
    }

    private int count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    @Test
    @DisplayName("두 회원이 같은 좌석을 동시에 선점하면 한 건만 성공하고 나머지는 409 SEAT_ALREADY_TAKEN이다")
    void sameSeat() throws Exception {
        Long seatId = seat();

        List<String> results = runTogether(
                () -> seatHoldService.hold(1L, SCHEDULE, seatId), () -> seatHoldService.hold(2L, SCHEDULE, seatId));

        assertThat(results).containsExactlyInAnyOrder("CREATED", "SEAT_ALREADY_TAKEN");
        assertThat(count("SELECT COUNT(*) FROM p_reservations WHERE status = 'HOLD'"))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM p_schedule_seats WHERE id = ? AND status = 'HOLD'", seatId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("같은 회원이 다른 좌석 두 장을 동시에 선점하면 한 건만 성공하고 나머지는 409 PURCHASE_LIMIT_EXCEEDED다")
    void sameUserTwoSeats() throws Exception {
        Long first = seat();
        Long second = seat();

        List<String> results = runTogether(
                () -> seatHoldService.hold(1L, SCHEDULE, first), () -> seatHoldService.hold(1L, SCHEDULE, second));

        assertThat(results).containsExactlyInAnyOrder("CREATED", "PURCHASE_LIMIT_EXCEEDED");
        assertThat(count("SELECT COUNT(*) FROM p_reservations WHERE user_id = 1"))
                .isEqualTo(1);
        // 실패한 쪽은 트랜잭션 전체가 롤백돼 좌석도 그대로다
        assertThat(count("SELECT COUNT(*) FROM p_schedule_seats WHERE schedule_id = ? AND status = 'HOLD'", SCHEDULE))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("같은 회원의 앞선 요청이 잠금 대기 중에 같은 좌석을 선점·커밋하면, 뒤의 요청은 잠근 뒤 다시 읽어 그 예약을 200으로 돌려준다")
    void sameUserSameSeatCommittedWhileWaiting() throws Exception {
        Long seatId = seat();
        CountDownLatch firstHoldsLock = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        // ① 같은 회원의 앞선 요청 역할: 좌석을 잠그고 이 회원의 예약을 만들어 좌석을 HOLD로 바꾼 뒤, 커밋하지 않고 기다린다
        Future<Long> first = pool.submit(() -> transactionTemplate.execute(status -> {
            jdbcTemplate.queryForObject("SELECT id FROM p_schedule_seats WHERE id = ? FOR UPDATE", Long.class, seatId);
            LocalDateTime expiresAt = NOW.plusMinutes(10);
            Long reservationId =
                    bookingFacade.hold(1L, TestHoldTargets.of(SCHEDULE), TestSeats.priced(seatId), expiresAt);
            jdbcTemplate.update(
                    "UPDATE p_schedule_seats SET status = 'HOLD', reservation_id = ?, hold_expires_at = ? WHERE id = ?",
                    reservationId,
                    expiresAt,
                    seatId);
            firstHoldsLock.countDown();
            await(releaseFirst);
            return reservationId;
        }));
        await(firstHoldsLock);

        // ② 실제 선점: findActiveHold는 ①의 커밋 전이라 "내 HOLD 없음"을 읽고, 좌석 잠금에서 ①을 기다린다
        Future<SeatHoldResult> second = pool.submit(() -> seatHoldService.hold(1L, SCHEDULE, seatId));
        waitUntilSomeoneWaitsForLock();

        // ①을 커밋하면 ②가 잠금을 얻는다. 잠근 좌석은 ①의 예약 HOLD이고, 다시 읽으면 이 회원의 예약이다
        releaseFirst.countDown();
        Long firstReservation = first.get(30, TimeUnit.SECONDS);
        SeatHoldResult result = second.get(30, TimeUnit.SECONDS);

        assertThat(result.created()).isFalse();
        assertThat(result.reservationId()).isEqualTo(firstReservation);
        assertThat(count("SELECT COUNT(*) FROM p_reservations WHERE user_id = 1"))
                .isEqualTo(1);
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

    /** 다른 연결이 행 잠금을 기다리는 상태가 될 때까지 기다린다. ②가 findActiveHold를 마치고 좌석 잠금에서 멈췄다는 신호다. */
    private void waitUntilSomeoneWaitsForLock() throws InterruptedException {
        for (int i = 0; i < 300; i++) {
            Integer waiting = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()",
                    Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new IllegalStateException("좌석 잠금을 기다리는 요청이 보이지 않는다");
    }

    @Test
    @DisplayName("요청 좌석의 다른 회원 선점이 판매 검증 중에 만료되면 가져오고, 그 예약의 다른 좌석도 함께 반환한다")
    void otherHoldExpiresDuringValidation() throws Exception {
        Long seat5 = seat();
        Long seat9 = seat();
        LocalDateTime expiresAt = NOW.plusMinutes(10);
        Long r1 = transactionTemplate.execute(status ->
                bookingFacade.hold(9L, TestHoldTargets.of(SCHEDULE), TestSeats.priced(seat5, seat9), expiresAt));
        jdbcTemplate.update(
                "UPDATE p_schedule_seats SET status = 'HOLD', reservation_id = ?, hold_expires_at = ? WHERE id IN (?, ?)",
                r1,
                expiresAt,
                seat5,
                seat9);
        // 1단계(좌석 읽기)에는 만료 1초 전이고, 판매 검증을 기다리는 사이 만료 시각이 지난다
        clock.set(expiresAt.minusSeconds(1));
        saleValidation.duringValidation(() -> clock.advance(Duration.ofSeconds(2)));

        SeatHoldResult result = seatHoldService.hold(1L, SCHEDULE, seat9);

        assertThat(result.created()).isTrue();
        assertThat(status("p_reservations", "reservation_id", r1)).isEqualTo("EXPIRED");
        assertThat(status("p_schedule_seats", "id", seat5)).isEqualTo("AVAILABLE");
        assertThat(status("p_schedule_seats", "id", seat9)).isEqualTo("HOLD");
    }

    @Test
    @DisplayName("다른 좌석에 있는 이 회원의 선점이 판매 검증 중에 만료되면 그 예약을 정리하고 새 좌석을 선점한다")
    void ownHoldExpiresDuringValidation() throws Exception {
        Long oldSeat = seat();
        Long newSeat = seat();
        Long old = seatHoldService.hold(1L, SCHEDULE, oldSeat).reservationId();
        clock.set(NOW.plusMinutes(10).minusSeconds(1));
        saleValidation.duringValidation(() -> clock.advance(Duration.ofSeconds(2)));

        SeatHoldResult result = seatHoldService.hold(1L, SCHEDULE, newSeat);

        assertThat(result.created()).isTrue();
        assertThat(status("p_reservations", "reservation_id", old)).isEqualTo("EXPIRED");
        assertThat(status("p_schedule_seats", "id", oldSeat)).isEqualTo("AVAILABLE");
        assertThat(status("p_schedule_seats", "id", newSeat)).isEqualTo("HOLD");
    }

    private String status(String table, String idColumn, Long id) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM " + table + " WHERE " + idColumn + " = ?", String.class, id);
    }
}
