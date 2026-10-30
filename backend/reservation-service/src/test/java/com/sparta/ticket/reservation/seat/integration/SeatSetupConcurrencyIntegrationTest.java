package com.sparta.ticket.reservation.seat.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.booking.support.MutableClock;
import com.sparta.ticket.reservation.seat.application.hold.SeatHoldService;
import com.sparta.ticket.reservation.seat.application.release.SeatReleaseService;
import com.sparta.ticket.reservation.seat.application.setup.SeatSetupCommand;
import com.sparta.ticket.reservation.seat.application.setup.SeatSetupResult;
import com.sparta.ticket.reservation.seat.application.setup.SeatSetupService;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeatSpec;
import com.sparta.ticket.reservation.seat.support.FakeSaleValidationPort;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 좌석 준비가 다른 작업과 동시에 올 때 좌석이 어긋나지 않는지 실제 트랜잭션 여러 개로 확인한다.
 * 순서는 우연에 맡기지 않는다. 테스트가 잠금을 쥔 트랜잭션(T0)으로 먼저 온 작업을 멈추고, 나중 작업이 같은 잠금을 기다리는 것을
 * pg_stat_activity로 확인한 뒤 풀어 준다. 잠금 순서는 좌석 준비가 회차 advisory → 좌석(id 오름차순), 선점·해제가 좌석 → 예약이라
 * 교착하지 않고, 각 테스트는 시간 제한 안에 끝나야 한다.
 *
 * Note: 테스트 공용 컨텍스트는 booking의 SeatPort를 가짜로 바꿔 두어, 결제 확정처럼 booking이 좌석을 바꾸는 경로는 같은 순서의 SQL로
 * 재현한다(SeatReleaseConcurrencyIntegrationTest와 같은 방식).
 */
class SeatSetupConcurrencyIntegrationTest extends ReservationIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 20, 0);
    private static final Long SCHEDULE = 1101L;
    private static final Long USER = 1L;
    private static final String ADVISORY_LOCK = "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))";
    private static final String SEAT_LOCK = "SELECT id FROM p_schedule_seats WHERE id = ? FOR UPDATE";

    @Autowired
    private SeatSetupService seatSetupService;

    @Autowired
    private SeatHoldService seatHoldService;

    @Autowired
    private SeatReleaseService seatReleaseService;

    @Autowired
    private FakeSaleValidationPort saleValidation;

    @Autowired
    private MutableClock clock;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

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

    /** 공연장 좌석들로 만든 준비 요청. 선점 테스트의 가짜 판매 검증과 같은 가격(150,000원)이다. */
    private static SeatSetupCommand command(long configVersion, long... venueSeatIds) {
        List<ScheduleSeatSpec> seats = LongStream.of(venueSeatIds)
                .mapToObj(id -> new ScheduleSeatSpec(
                        id, 7L, "VIP", FakeSaleValidationPort.DEFAULT_PRICE, "A", "1", (int) id, true))
                .toList();
        return new SeatSetupCommand("req-" + configVersion, SCHEDULE, configVersion, seats);
    }

    /** 공연장 좌석 1~count. */
    private static SeatSetupCommand command(long configVersion, int count) {
        return command(configVersion, LongStream.rangeClosed(1, count).toArray());
    }

    private Long seatId(long venueSeatId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM p_schedule_seats WHERE schedule_id = ? AND venue_seat_id = ?",
                Long.class,
                SCHEDULE,
                venueSeatId);
    }

    private Map<String, Object> seatRow(Long seatId) {
        return jdbcTemplate.queryForMap(
                "SELECT status, config_version, deleted_at FROM p_schedule_seats WHERE id = ?", seatId);
    }

    private long version(Long seatId) {
        return ((Number) seatRow(seatId).get("config_version")).longValue();
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

    /** T0: sql로 잠금을 잡고 commit 신호까지 기다리는 트랜잭션. 반환 시점에는 이미 잠금을 쥐고 있다. */
    private Future<?> holdLock(String sql, Object arg, CountDownLatch commit) {
        return holdInTransaction(() -> jdbcTemplate.queryForList(sql, arg), commit);
    }

    /** T0: work를 실행한 뒤 commit 신호까지 기다리는 트랜잭션. 반환 시점에는 work가 끝나 있다(잠금을 쥔 상태). */
    private Future<?> holdInTransaction(Runnable work, CountDownLatch commit) {
        CountDownLatch done = new CountDownLatch(1);
        Future<?> holder = pool.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            work.run();
            done.countDown();
            await(commit);
        }));
        await(done);
        return holder;
    }

    @Test
    @DisplayName("같은 준비 요청 두 개가 동시에 오면 회차 잠금으로 차례로 처리돼 좌석은 한 번만 만들어지고 둘 다 같은 결과다")
    void sameRequestTwice() throws Exception {
        CountDownLatch commit = new CountDownLatch(1);
        Future<?> holder = holdLock(ADVISORY_LOCK, "seat-setup:" + SCHEDULE, commit);

        // 두 요청 모두 회차 잠금에서 기다린다. 잠금이 없으면 둘 다 INSERT해 한쪽이 UK 위반으로 끝난다
        Future<SeatSetupResult> first = pool.submit(() -> seatSetupService.setup(command(1, 40)));
        Future<SeatSetupResult> second = pool.submit(() -> seatSetupService.setup(command(1, 40)));
        waitUntilLockWaiters("pg_advisory_xact_lock", 2);
        commit.countDown();
        holder.get(30, TimeUnit.SECONDS);

        SeatSetupResult a = first.get(30, TimeUnit.SECONDS);
        SeatSetupResult b = second.get(30, TimeUnit.SECONDS);
        assertThat(a).isEqualTo(b);
        assertThat(a.seatCount()).isEqualTo(40);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM p_schedule_seats WHERE schedule_id = ?", Integer.class, SCHEDULE))
                .isEqualTo(40);
    }

    @Test
    @DisplayName("교체 요청(v2) 두 개가 동시에 오면 한쪽이 바꾸고, 늦은 쪽은 같은 버전이 반영된 것을 보고 같은 결과다")
    void sameReplacementTwice() throws Exception {
        seatSetupService.setup(command(1, 3));
        CountDownLatch commit = new CountDownLatch(1);
        Future<?> holder = holdLock(ADVISORY_LOCK, "seat-setup:" + SCHEDULE, commit);

        Future<SeatSetupResult> first = pool.submit(() -> seatSetupService.setup(command(2, 1L, 2L)));
        Future<SeatSetupResult> second = pool.submit(() -> seatSetupService.setup(command(2, 1L, 2L)));
        waitUntilLockWaiters("pg_advisory_xact_lock", 2);
        commit.countDown();
        holder.get(30, TimeUnit.SECONDS);

        assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(second.get(30, TimeUnit.SECONDS));
        assertThat(first.get().seatCount()).isEqualTo(2);
        assertThat(seatRow(seatId(3)).get("deleted_at")).isNotNull();
    }

    @Test
    @DisplayName("v3가 v2보다 먼저 처리되면 늦게 온 v2는 409 SEAT_SETUP_STALE_VERSION이고 v3 구성이 남는다")
    void newerVersionFirst() throws Exception {
        seatSetupService.setup(command(1, 3));
        CountDownLatch commit = new CountDownLatch(1);
        Future<?> holder = holdLock(ADVISORY_LOCK, "seat-setup:" + SCHEDULE, commit);

        Future<String> v3 = pool.submit(() -> outcome(() -> seatSetupService.setup(command(3, 1L, 2L))));
        waitUntilLockWaiters("pg_advisory_xact_lock", 1);
        Future<String> v2 = pool.submit(() -> outcome(() -> seatSetupService.setup(command(2, 3))));
        waitUntilLockWaiters("pg_advisory_xact_lock", 2);
        commit.countDown();
        holder.get(30, TimeUnit.SECONDS);

        assertThat(v3.get(30, TimeUnit.SECONDS)).isEqualTo("OK");
        assertThat(v2.get(30, TimeUnit.SECONDS)).isEqualTo("SEAT_SETUP_STALE_VERSION");
        assertThat(version(seatId(1))).isEqualTo(3L);
        assertThat(seatRow(seatId(3)).get("deleted_at")).isNotNull();
    }

    @Test
    @DisplayName("같은 버전 재요청·낮은 버전은 좌석을 잠그지 않아, 좌석 잠금을 다른 트랜잭션이 쥐고 있어도 기다리지 않고 끝난다")
    void noOpRequestsDoNotLockSeats() throws Exception {
        seatSetupService.setup(command(2, 3));
        CountDownLatch commit = new CountDownLatch(1);
        Future<?> holder = holdLock(SEAT_LOCK, seatId(1), commit);
        try {
            // 좌석을 잠그면 대기 상한(3초)까지 기다렸다가 실패하므로, 1초 안에 끝나면 잠그지 않은 것이다
            Future<SeatSetupResult> same = pool.submit(() -> seatSetupService.setup(command(2, 3)));
            assertThat(same.get(1, TimeUnit.SECONDS).seatCount()).isEqualTo(3);
            Future<String> stale = pool.submit(() -> outcome(() -> seatSetupService.setup(command(1, 3))));
            assertThat(stale.get(1, TimeUnit.SECONDS)).isEqualTo("SEAT_SETUP_STALE_VERSION");
        } finally {
            commit.countDown();
            holder.get(30, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("다른 준비가 회차 잠금을 오래 쥐면 대기 상한(3초) 뒤 503 SERVICE_UNAVAILABLE이고 아무것도 저장하지 않는다")
    void lockWaitTimesOut() throws Exception {
        CountDownLatch commit = new CountDownLatch(1);
        Future<?> holder = holdLock(ADVISORY_LOCK, "seat-setup:" + SCHEDULE, commit);
        try {
            long started = System.nanoTime();
            mockMvc.perform(post("/internal/schedules/{scheduleId}/seat-setups", SCHEDULE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"requestId":"req-1","scheduleId":%d,"configVersion":1,"seats":[{"venueSeatId":1,
                                    "venueSeatGradeId":7,"gradeName":"VIP","basePrice":150000,"isSellable":true,
                                    "section":"A","rowLabel":"1","seatNumber":1}]}
                                    """.formatted(SCHEDULE)))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
        } finally {
            commit.countDown();
            holder.get(30, TimeUnit.SECONDS);
        }
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM p_schedule_seats WHERE schedule_id = ?", Integer.class, SCHEDULE))
                .isZero();

        // 잠금이 풀린 뒤 공연 쪽이 같은 요청을 다시 보내면 200이다
        assertThat(seatSetupService.setup(command(1, 1)).seatCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("선점이 좌석을 먼저 잠그면, 기다리던 높은 버전 준비는 HOLD를 보고 409 SEAT_SETUP_IN_USE이고 선점은 그대로다")
    void holdFirstThenReplace() throws Exception {
        seatSetupService.setup(command(1, 3));
        Long seat = seatId(1);
        CountDownLatch commit = new CountDownLatch(1);
        Future<?> holder = holdLock(SEAT_LOCK, seat, commit);

        Future<String> hold = pool.submit(() -> outcome(() -> seatHoldService.hold(USER, SCHEDULE, seat)));
        waitUntilLockWaiters("p_schedule_seats", 1);
        Future<String> setup = pool.submit(() -> outcome(() -> seatSetupService.setup(command(2, 3))));
        waitUntilLockWaiters("p_schedule_seats", 2);
        commit.countDown();
        holder.get(30, TimeUnit.SECONDS);

        assertThat(hold.get(30, TimeUnit.SECONDS)).isEqualTo("OK");
        assertThat(setup.get(30, TimeUnit.SECONDS)).isEqualTo("SEAT_SETUP_IN_USE");
        assertThat(seatRow(seat)).containsEntry("status", "HOLD");
        assertThat(version(seat)).isEqualTo(1L);
    }

    @Test
    @DisplayName("준비가 좌석을 먼저 잠가 새 구성으로 바꾸면, 기다리던 선점은 바뀐 구성 버전을 보고 409 SEAT_CONFIG_VERSION_MISMATCH다")
    void replaceFirstThenHold() throws Exception {
        seatSetupService.setup(command(1, 3));
        Long seat = seatId(1);
        CountDownLatch commit = new CountDownLatch(1);
        Future<?> holder = holdLock(SEAT_LOCK, seat, commit);

        Future<String> setup = pool.submit(() -> outcome(() -> seatSetupService.setup(command(2, 3))));
        waitUntilLockWaiters("p_schedule_seats", 1);
        Future<String> hold = pool.submit(() -> outcome(() -> seatHoldService.hold(USER, SCHEDULE, seat)));
        waitUntilLockWaiters("p_schedule_seats", 2);
        commit.countDown();
        holder.get(30, TimeUnit.SECONDS);

        assertThat(setup.get(30, TimeUnit.SECONDS)).isEqualTo("OK");
        assertThat(hold.get(30, TimeUnit.SECONDS)).isEqualTo("SEAT_CONFIG_VERSION_MISMATCH");
        assertThat(seatRow(seat)).containsEntry("status", "AVAILABLE");
        assertThat(version(seat)).isEqualTo(2L);
    }

    @Test
    @DisplayName("준비가 좌석을 빼는(논리 삭제) 동안 그 좌석의 선점이 기다리면, 선점은 삭제된 좌석을 찾지 못해 404 SEAT_NOT_FOUND다")
    void replaceRemovesSeatWhileHoldWaits() throws Exception {
        seatSetupService.setup(command(1, 3));
        Long seat = seatId(1);
        CountDownLatch commit = new CountDownLatch(1);
        Future<?> holder = holdLock(SEAT_LOCK, seat, commit);

        Future<String> setup = pool.submit(() -> outcome(() -> seatSetupService.setup(command(2, 2L, 3L))));
        waitUntilLockWaiters("p_schedule_seats", 1);
        Future<String> hold = pool.submit(() -> outcome(() -> seatHoldService.hold(USER, SCHEDULE, seat)));
        waitUntilLockWaiters("p_schedule_seats", 2);
        commit.countDown();
        holder.get(30, TimeUnit.SECONDS);

        // 선점의 잠금 조회(deleted_at IS NULL)는 READ COMMITTED에서 잠근 뒤 조건을 다시 확인해 삭제된 행을 뺀다
        assertThat(setup.get(30, TimeUnit.SECONDS)).isEqualTo("OK");
        assertThat(hold.get(30, TimeUnit.SECONDS)).isEqualTo("SEAT_NOT_FOUND");
        assertThat(seatRow(seat).get("deleted_at")).isNotNull();
        assertThat(seatRow(seat)).containsEntry("status", "AVAILABLE");
    }

    @Test
    @DisplayName("해제가 HOLD를 풀고 커밋하는 동안 준비가 기다리면, 준비는 풀린 좌석을 보고 교체에 성공한다")
    void releaseFirstThenReplace() throws Exception {
        seatSetupService.setup(command(1, 3));
        Long seat = seatId(1);
        Long reservationId = seatHoldService.hold(USER, SCHEDULE, seat).reservationId();
        CountDownLatch commit = new CountDownLatch(1);
        // 해제가 좌석을 잠근 뒤 예약 UPDATE에서 멈추도록 예약 행을 쥔다
        Future<?> holder = holdLock(
                "SELECT reservation_id FROM p_reservations WHERE reservation_id = ? FOR UPDATE", reservationId, commit);

        Future<String> release = pool.submit(() -> outcome(() -> {
            seatReleaseService.release(USER, SCHEDULE, reservationId);
            return null;
        }));
        waitUntilLockWaiters("p_reservations", 1);
        Future<String> setup = pool.submit(() -> outcome(() -> seatSetupService.setup(command(2, 3))));
        waitUntilLockWaiters("p_schedule_seats", 1);
        commit.countDown();
        holder.get(30, TimeUnit.SECONDS);

        assertThat(release.get(30, TimeUnit.SECONDS)).isEqualTo("OK");
        assertThat(setup.get(30, TimeUnit.SECONDS)).isEqualTo("OK");
        assertThat(seatRow(seat)).containsEntry("status", "AVAILABLE");
        assertThat(version(seat)).isEqualTo(2L);
    }

    @Test
    @DisplayName("준비가 좌석 1을 잡고 2를 기다리는 동안 선점이 좌석 2·3을 노려도, 둘 다 id 오름차순이라 교착 없이 차례로 끝난다")
    void partialLocksDoNotDeadlock() throws Exception {
        seatSetupService.setup(command(1, 3));
        Long seat2 = seatId(2);
        Long seat3 = seatId(3);
        // 회원의 만료된 선점(좌석 3). 다시 선점하면 좌석 2와 함께 좌석 3도 잠근다
        seatHoldService.hold(USER, SCHEDULE, seat3);
        clock.set(NOW.plusMinutes(11));
        CountDownLatch commit = new CountDownLatch(1);
        Future<?> holder = holdLock(SEAT_LOCK, seat2, commit);

        // 준비: 좌석 1을 잡고 2에서 기다린다. 선점: 좌석 {2, 3} 중 2에서 기다린다(3은 아직 못 잡음)
        Future<String> setup = pool.submit(() -> outcome(() -> seatSetupService.setup(command(2, 3))));
        waitUntilLockWaiters("p_schedule_seats", 1);
        Future<String> hold = pool.submit(() -> outcome(() -> seatHoldService.hold(USER, SCHEDULE, seat2)));
        waitUntilLockWaiters("p_schedule_seats", 2);
        commit.countDown();
        holder.get(30, TimeUnit.SECONDS);

        // 준비가 먼저 2·3을 잡아 만료된 HOLD(좌석 3)를 보고 409, 그 뒤 선점이 자기 만료 선점을 정리하고 좌석 2를 잡는다
        assertThat(setup.get(30, TimeUnit.SECONDS)).isEqualTo("SEAT_SETUP_IN_USE");
        assertThat(hold.get(30, TimeUnit.SECONDS)).isEqualTo("OK");
        assertThat(seatRow(seat2)).containsEntry("status", "HOLD");
        assertThat(seatRow(seat3)).containsEntry("status", "AVAILABLE");
        assertThat(version(seat2)).isEqualTo(1L);
    }

    @Test
    @DisplayName("결제 확정이 좌석을 SOLD로 바꾸는 동안 준비가 기다리면, 준비는 SOLD를 보고 409 SEAT_SETUP_IN_USE이고 좌석은 SOLD 그대로다")
    void confirmFirstThenReplace() throws Exception {
        seatSetupService.setup(command(1, 3));
        Long seat = seatId(1);
        CountDownLatch commit = new CountDownLatch(1);
        // 결제 확정(lockSeats → 예약 조건부 변경 → sell)의 좌석 쪽을 같은 순서의 SQL로 재현한다. 예약 행은 이 테스트와 무관하다
        Future<?> holder = holdInTransaction(
                () -> {
                    jdbcTemplate.queryForList(SEAT_LOCK, seat);
                    jdbcTemplate.update(
                            "UPDATE p_schedule_seats SET status = 'SOLD', reservation_id = 55, hold_expires_at = NULL"
                                    + " WHERE id = ?",
                            seat);
                },
                commit);

        Future<String> setup = pool.submit(() -> outcome(() -> seatSetupService.setup(command(2, 3))));
        waitUntilLockWaiters("p_schedule_seats", 1);
        commit.countDown();
        holder.get(30, TimeUnit.SECONDS);

        assertThat(setup.get(30, TimeUnit.SECONDS)).isEqualTo("SEAT_SETUP_IN_USE");
        assertThat(seatRow(seat)).containsEntry("status", "SOLD");
        assertThat(version(seat)).isEqualTo(1L);
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

    /** 쿼리 문장에 text가 들어간 연결 중 잠금을 기다리는 연결이 count개 이상이 될 때까지 기다린다. */
    private void waitUntilLockWaiters(String text, int count) throws InterruptedException {
        for (int i = 0; i < 300; i++) {
            Integer waiting = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()"
                            + " AND query ILIKE ?",
                    Integer.class,
                    "%" + text + "%");
            if (waiting != null && waiting >= count) {
                return;
            }
            Thread.sleep(20);
        }
        throw new IllegalStateException(text + " 잠금을 기다리는 요청이 " + count + "개 보이지 않는다");
    }
}
