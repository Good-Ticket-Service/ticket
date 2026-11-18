package com.sparta.ticket.reservation.seat.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.booking.application.expiry.HoldExpiryService;
import com.sparta.ticket.reservation.booking.infrastructure.seat.SeatFacadeAdapter;
import com.sparta.ticket.reservation.booking.support.FakeSeatPort;
import com.sparta.ticket.reservation.booking.support.MutableClock;
import com.sparta.ticket.reservation.seat.application.facade.SeatFacade;
import com.sparta.ticket.reservation.seat.application.hold.HoldSeatLookup;
import com.sparta.ticket.reservation.seat.application.hold.RedisSeatStateLoader;
import com.sparta.ticket.reservation.seat.application.hold.RedisSeatStateReconciler;
import com.sparta.ticket.reservation.seat.application.hold.SeatHoldConversionService;
import com.sparta.ticket.reservation.seat.application.hold.SeatHoldMode;
import com.sparta.ticket.reservation.seat.application.hold.SeatHoldResult;
import com.sparta.ticket.reservation.seat.application.hold.SeatHoldService;
import com.sparta.ticket.reservation.seat.application.port.SeatHoldStore;
import com.sparta.ticket.reservation.seat.application.release.SeatReleaseService;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeat;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeatRepository;
import com.sparta.ticket.reservation.seat.support.FakeSaleValidationPort;
import com.sparta.ticket.reservation.seat.support.MutableSeatHoldModeSwitch;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Redis 점유 판정(#459)을 실제 DB·Redis로 확인한다. 회차를 Redis에 올리면 선점은 DB 좌석 상태가 아니라 Redis에 올린 점유 좌석으로
 * 판정한다. 그래서 DB와 Redis를 일부러 어긋나게 만들어, 판정이 어느 쪽에서 나왔는지 본다.
 */
class RedisSeatStateIntegrationTest extends ReservationIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 20, 0);
    private static final Long SCHEDULE = 704L;
    private static final Long USER = 1L;
    private static final Long OTHER_USER = 2L;
    private static final String PREFIX = "seathold:{s" + SCHEDULE + "}:";

    @Autowired
    private SeatHoldService seatHoldService;

    @Autowired
    private SeatHoldConversionService conversionService;

    @Autowired
    private RedisSeatStateLoader stateLoader;

    @Autowired
    private SeatHoldStore store;

    @Autowired
    private RedisSeatStateReconciler reconciler;

    @Autowired
    private HoldSeatLookup holdSeats;

    @Autowired
    private SeatReleaseService seatReleaseService;

    @Autowired
    private HoldExpiryService holdExpiryService;

    /** 공유 테스트 컨텍스트에서 booking의 좌석 포트는 가짜다. 만료 스케줄러가 실제 좌석을 풀게 실제 어댑터로 넘긴다. */
    @Autowired
    private FakeSeatPort bookingSeats;

    @Autowired
    private SeatFacadeAdapter seatFacadeAdapter;

    @Autowired
    private SeatFacade seatFacade;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ScheduleSeatRepository scheduleSeatRepository;

    @Autowired
    private FakeSaleValidationPort saleValidation;

    @Autowired
    private MutableSeatHoldModeSwitch modeSwitch;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redis;

    private long nextVenueSeatId = 1;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM p_schedule_seats");
        jdbcTemplate.update("DELETE FROM p_tickets");
        jdbcTemplate.update("DELETE FROM p_reservations");
        Set<String> keys = redis.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        saleValidation.reset();
        clock.set(NOW);
        modeSwitch.set(SeatHoldMode.REDIS);
        // 테스트마다 같은 회차 ID에 좌석을 새로 만들어 고정 정보 캐시를 비운다
        holdSeats.invalidate(SCHEDULE);
        bookingSeats.useReal(seatFacadeAdapter);
    }

    @AfterEach
    void tearDown() {
        modeSwitch.reset();
        bookingSeats.reset();
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

    /** DB 모드로 선점해 예약을 만든다. */
    private Long dbHold(Long userId, Long seatId) {
        modeSwitch.set(SeatHoldMode.DB);
        try {
            return seatHoldService.hold(userId, SCHEDULE, seatId).reservationId();
        } finally {
            modeSwitch.set(SeatHoldMode.REDIS);
        }
    }

    private void sell(Long reservationId) {
        jdbcTemplate.update(
                "UPDATE p_schedule_seats SET status = 'SOLD', hold_expires_at = NULL WHERE reservation_id = ?",
                reservationId);
    }

    /** 예약을 거치지 않고 DB 좌석만 AVAILABLE로 돌린다. Redis에 알리지 않은 해제를 흉내 낸다. */
    private void freeInDbOnly(Long seatId) {
        jdbcTemplate.update(
                "UPDATE p_schedule_seats SET status = 'AVAILABLE', reservation_id = NULL, hold_expires_at = NULL"
                        + " WHERE id = ?",
                seatId);
    }

    private String errorCode(Runnable action) {
        try {
            action.run();
        } catch (BusinessException e) {
            return e.getErrorCode().code();
        }
        throw new AssertionError("오류가 나지 않았다");
    }

    private Map<Object, Object> reserved() {
        return redis.opsForHash().entries(PREFIX + "reserved");
    }

    @Test
    @DisplayName("올리면 DB에서 HOLD·SOLD인 좌석만 예약 ID와 함께 Redis에 남고 올림 표시가 생긴다")
    void loadsReservedSeats() {
        Long held = seat();
        Long sold = seat();
        seat();
        Long heldReservation = dbHold(OTHER_USER, held);
        Long soldReservation = dbHold(3L, sold);
        sell(soldReservation);

        stateLoader.reload(SCHEDULE);

        assertThat(reserved())
                .containsOnly(
                        Map.entry(String.valueOf(held), String.valueOf(heldReservation)),
                        Map.entry(String.valueOf(sold), String.valueOf(soldReservation)));
        assertThat(redis.hasKey(PREFIX + "loaded")).isTrue();
        assertThat(redis.getExpire(PREFIX + "loaded")).isPositive();
    }

    @Test
    @DisplayName("올린 회차는 DB 좌석 상태가 아니라 Redis로 판정한다. DB에서만 풀린 좌석은 Redis가 지울 때까지 409다")
    void judgesByRedisNotDb() {
        Long seatId = seat();
        dbHold(OTHER_USER, seatId);
        stateLoader.reload(SCHEDULE);
        freeInDbOnly(seatId);

        assertThat(errorCode(() -> seatHoldService.hold(USER, SCHEDULE, seatId)))
                .isEqualTo("SEAT_ALREADY_TAKEN");
        assertThat(redis.hasKey(PREFIX + "user:" + USER)).isFalse();
    }

    @Test
    @DisplayName("Redis에 없는 DB 점유는 선점이 통과하지만 결제하기의 좌석 잠금이 409로 막아 예약은 한 건이다")
    void redisMissFallsToConversionLock() {
        Long seatId = seat();
        stateLoader.reload(SCHEDULE);
        // 올린 뒤 Redis를 거치지 않고 생긴 DB 점유(DB 모드 선점). Redis는 비었다고 본다
        dbHold(OTHER_USER, seatId);

        SeatHoldResult hold = seatHoldService.hold(USER, SCHEDULE, seatId);

        assertThat(hold.holdId()).isNotNull();
        assertThat(errorCode(() -> conversionService.convert(USER, SCHEDULE, hold.holdId())))
                .isEqualTo("SEAT_ALREADY_TAKEN");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM p_reservations", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("올린 회차에서 결제하기가 끝나면 좌석을 Redis 점유에 넣어 다른 회원의 선점은 DB를 읽지 않고 409다")
    void conversionRecordsReservedSeat() {
        Long seatId = seat();
        stateLoader.reload(SCHEDULE);
        SeatHoldResult hold = seatHoldService.hold(USER, SCHEDULE, seatId);

        SeatHoldResult converted = conversionService.convert(USER, SCHEDULE, hold.holdId());

        assertThat(reserved()).containsEntry(String.valueOf(seatId), String.valueOf(converted.reservationId()));
        assertThat(redis.getExpire(PREFIX + "reserved")).isPositive();
        // DB에서만 풀어도 Redis가 잡고 있어 409다. 판정이 Redis에서 나왔다는 뜻이다
        freeInDbOnly(seatId);
        assertThat(errorCode(() -> seatHoldService.hold(OTHER_USER, SCHEDULE, seatId)))
                .isEqualTo("SEAT_ALREADY_TAKEN");
    }

    @Test
    @DisplayName("올림 표시가 사라진 회차는 결제하기가 끝나도 Redis 점유에 넣지 않는다")
    void conversionSkipsUnloadedSchedule() {
        Long seatId = seat();
        stateLoader.reload(SCHEDULE);
        SeatHoldResult hold = seatHoldService.hold(USER, SCHEDULE, seatId);
        // 올린 값의 수명이 끝난 것과 같다
        redis.delete(List.of(PREFIX + "loaded", PREFIX + "reserved"));

        conversionService.convert(USER, SCHEDULE, hold.holdId());

        assertThat(redis.hasKey(PREFIX + "reserved")).isFalse();
    }

    @Test
    @DisplayName("예약으로 바꾼 회원이 같은 좌석을 다시 누르면 만료 전 HOLD면 그 예약을 200으로 준다")
    void sameUserConvertedSeatReturnsReservation() {
        Long seatId = seat();
        stateLoader.reload(SCHEDULE);
        SeatHoldResult hold = seatHoldService.hold(USER, SCHEDULE, seatId);
        SeatHoldResult converted = conversionService.convert(USER, SCHEDULE, hold.holdId());

        SeatHoldResult again = seatHoldService.hold(USER, SCHEDULE, seatId);

        assertThat(again.created()).isFalse();
        assertThat(again.reservationId()).isEqualTo(converted.reservationId());
        assertThat(again.holdExpiresAt()).isEqualTo(NOW.plusMinutes(10));
    }

    @Test
    @DisplayName("예약으로 바꾼 좌석의 만료 시각이 지났으면 DB가 풀 때까지 같은 회원이 다시 눌러도 409다(만료 반영 지연)")
    void sameUserExpiredSeatWaitsForRelease() {
        Long seatId = seat();
        stateLoader.reload(SCHEDULE);
        SeatHoldResult hold = seatHoldService.hold(USER, SCHEDULE, seatId);
        conversionService.convert(USER, SCHEDULE, hold.holdId());
        clock.advance(Duration.ofMinutes(11));

        assertThat(errorCode(() -> seatHoldService.hold(USER, SCHEDULE, seatId)))
                .isEqualTo("SEAT_ALREADY_TAKEN");
    }

    @Test
    @DisplayName("올리지 않은 회차는 이번 요청을 DB 좌석으로 판정하고, 뒤에서 회차를 올린다")
    void unloadedScheduleFallsBackAndLoads() {
        Long held = seat();
        Long free = seat();
        Long reservationId = dbHold(OTHER_USER, held);

        assertThat(errorCode(() -> seatHoldService.hold(USER, SCHEDULE, held))).isEqualTo("SEAT_ALREADY_TAKEN");
        assertThat(seatHoldService.hold(USER, SCHEDULE, free).created()).isTrue();

        await().atMost(Duration.ofSeconds(5)).until(() -> Boolean.TRUE.equals(redis.hasKey(PREFIX + "loaded")));
        assertThat(reserved()).containsOnly(Map.entry(String.valueOf(held), String.valueOf(reservationId)));
    }

    @Test
    @DisplayName("올리기는 이미 있는 좌석 값을 덮지 않는다. DB를 읽은 뒤 결제하기가 넣은 더 새 예약을 지키기 위해서다")
    void loadDoesNotOverwrite() {
        store.loadReserved(SCHEDULE, Map.of(10L, 200L), false, Duration.ofHours(1));

        store.loadReserved(SCHEDULE, Map.of(10L, 100L, 11L, 101L), true, Duration.ofHours(1));

        assertThat(reserved()).containsOnly(Map.entry("10", "200"), Map.entry("11", "101"));
        assertThat(redis.hasKey(PREFIX + "loaded")).isTrue();
    }

    @Test
    @DisplayName("올림 표시는 마지막 묶음에서만 남는다. 묶음 중간에 선점이 들어와도 덜 올린 점유로 판정하지 않는다")
    void loadedOnlyAfterLastChunk() {
        store.loadReserved(SCHEDULE, Map.of(10L, 200L), false, Duration.ofHours(1));

        assertThat(redis.hasKey(PREFIX + "loaded")).isFalse();
    }

    /** 올린 회차에서 Redis 선점을 결제하기까지 마치고 예약 ID를 돌려준다. */
    private Long convertedReservation(Long userId, Long seatId) {
        SeatHoldResult hold = seatHoldService.hold(userId, SCHEDULE, seatId);
        return conversionService.convert(userId, SCHEDULE, hold.holdId()).reservationId();
    }

    @Test
    @DisplayName("회원이 예약을 해제하면 커밋 뒤 Redis 점유 좌석에서도 지워 다른 회원이 바로 선점한다")
    void userReleaseFreesRedis() {
        Long seatId = seat();
        stateLoader.reload(SCHEDULE);
        Long reservationId = convertedReservation(USER, seatId);

        seatReleaseService.release(USER, SCHEDULE, reservationId);

        assertThat(reserved()).doesNotContainKey(String.valueOf(seatId));
        assertThat(seatHoldService.hold(OTHER_USER, SCHEDULE, seatId).created()).isTrue();
    }

    @Test
    @DisplayName("만료 스케줄러가 예약을 만료하면 커밋 뒤 Redis 점유 좌석에서도 지운다. 그 전까지는 409다")
    void expirySchedulerFreesRedis() {
        Long seatId = seat();
        stateLoader.reload(SCHEDULE);
        convertedReservation(USER, seatId);
        clock.advance(Duration.ofMinutes(11));
        assertThat(errorCode(() -> seatHoldService.hold(OTHER_USER, SCHEDULE, seatId)))
                .isEqualTo("SEAT_ALREADY_TAKEN");

        assertThat(holdExpiryService.expireDue(10, Duration.ofSeconds(5))).isEqualTo(1);

        assertThat(reserved()).doesNotContainKey(String.valueOf(seatId));
        assertThat(seatHoldService.hold(OTHER_USER, SCHEDULE, seatId).created()).isTrue();
    }

    @Test
    @DisplayName("올림 표시가 사라진 회차에서 만료 선점 좌석을 다시 잡아 결제하기하면, 만료 선점을 정리하고 Redis 점유가 새 예약이 된다")
    void expiredCleanupInConversionUpdatesRedis() {
        Long seatId = seat();
        stateLoader.reload(SCHEDULE);
        Long expired = convertedReservation(USER, seatId);
        clock.advance(Duration.ofMinutes(11));
        // 올림 표시만 사라져 이번 선점은 DB를 읽는다. 만료 선점은 결제 중이 아니라 빈 좌석으로 본다
        redis.delete(PREFIX + "loaded");

        SeatHoldResult hold = seatHoldService.hold(OTHER_USER, SCHEDULE, seatId);
        await().atMost(Duration.ofSeconds(5)).until(() -> Boolean.TRUE.equals(redis.hasKey(PREFIX + "loaded")));
        // 결제하기 트랜잭션이 좌석을 잠근 뒤 만료 선점을 정리하고 같은 좌석으로 새 예약을 만든다
        SeatHoldResult converted = conversionService.convert(OTHER_USER, SCHEDULE, hold.holdId());

        assertThat(converted.reservationId()).isNotEqualTo(expired);
        assertThat(reserved())
                .containsOnly(Map.entry(String.valueOf(seatId), String.valueOf(converted.reservationId())));
    }

    @Test
    @DisplayName("DB 모드에서는 DB가 좌석을 풀어도 Redis를 부르지 않는다(Redis 장애 때의 대체 경로)")
    void syncSkippedInDbMode() {
        Long seatId = seat();
        stateLoader.reload(SCHEDULE);
        Long reservationId = convertedReservation(USER, seatId);
        modeSwitch.set(SeatHoldMode.DB);

        seatReleaseService.release(USER, SCHEDULE, reservationId);

        assertThat(reserved()).containsEntry(String.valueOf(seatId), String.valueOf(reservationId));
    }

    @Test
    @DisplayName("정합 작업은 DB에서 풀렸는데 Redis에 남은 좌석만 지우고, DB와 같은 좌석은 남긴다")
    void reconcilerRemovesOnlyStale() {
        Long stale = seat();
        Long kept = seat();
        stateLoader.reload(SCHEDULE);
        convertedReservation(USER, stale);
        Long keptReservation = convertedReservation(OTHER_USER, kept);
        freeInDbOnly(stale);

        assertThat(reconciler.reconcile(SCHEDULE)).isEqualTo(1);

        assertThat(reserved()).containsOnly(Map.entry(String.valueOf(kept), String.valueOf(keptReservation)));
        assertThat(seatHoldService.hold(3L, SCHEDULE, stale).created()).isTrue();
    }

    @Test
    @DisplayName("정합 작업은 DB 모드 동안 잡혀 Redis에 없는 좌석을 채워, 다른 회원의 선점을 409로 막는다")
    void reconcilerFillsMissing() {
        Long seatId = seat();
        stateLoader.reload(SCHEDULE);
        // DB 모드에서는 Redis를 부르지 않아 이 선점이 Redis에 없다
        Long reservationId = dbHold(USER, seatId);
        assertThat(reserved()).isEmpty();

        reconciler.reconcile(SCHEDULE);

        assertThat(reserved()).containsOnly(Map.entry(String.valueOf(seatId), String.valueOf(reservationId)));
        assertThat(errorCode(() -> seatHoldService.hold(OTHER_USER, SCHEDULE, seatId)))
                .isEqualTo("SEAT_ALREADY_TAKEN");
    }

    @Test
    @DisplayName("이 서버가 올리지 않았는데 올림 표시가 남은 회차(서버 재시작 뒤)는 선점이 처음 쓸 때 바로 정합한다")
    void firstUseAfterRestartReconcilesImmediately() {
        Long taken = seat();
        Long free = seat();
        Long reservationId = dbHold(OTHER_USER, taken);
        // 앞선 서버가 남긴 표시와 같다. 이 서버는 이 회차를 올린 적이 없다
        store.loadReserved(SCHEDULE, Map.of(), true, Duration.ofHours(1));
        stateLoader.forget(SCHEDULE);

        assertThat(seatHoldService.hold(USER, SCHEDULE, free).created()).isTrue();

        await().atMost(Duration.ofSeconds(5)).until(() -> reserved().containsKey(String.valueOf(taken)));
        assertThat(reserved()).containsOnly(Map.entry(String.valueOf(taken), String.valueOf(reservationId)));
    }

    @Test
    @DisplayName("정합 작업은 올림 표시가 있는 회차의 수명을 늘리고, 표시가 없는 회차는 늘리지 않는다")
    void reconcilerExtendsOnlyLoaded() {
        Long seatId = seat();
        stateLoader.reload(SCHEDULE);
        convertedReservation(USER, seatId);
        redis.expire(PREFIX + "loaded", Duration.ofSeconds(60));
        redis.expire(PREFIX + "reserved", Duration.ofSeconds(60));

        reconciler.reconcile(SCHEDULE);

        assertThat(redis.getExpire(PREFIX + "loaded"))
                .isGreaterThan(Duration.ofHours(23).toSeconds());
        assertThat(redis.getExpire(PREFIX + "reserved"))
                .isGreaterThan(Duration.ofHours(23).toSeconds());

        redis.delete(PREFIX + "loaded");
        redis.expire(PREFIX + "reserved", Duration.ofSeconds(60));
        reconciler.reconcile(SCHEDULE);

        assertThat(redis.hasKey(PREFIX + "loaded")).isFalse();
        assertThat(redis.getExpire(PREFIX + "reserved")).isLessThanOrEqualTo(60);
    }

    @Test
    @DisplayName("Redis 점유 판정의 선점은 DB가 아니라 좌석 고정 정보 캐시를 읽고, 낡은 값은 결제하기의 재판정이 막는다")
    void holdReadsFixedSeatCache() {
        Long seatId = seat();
        stateLoader.reload(SCHEDULE);
        holdSeats.warm(SCHEDULE);
        // 캐시를 채운 뒤 DB에서만 판매 제외로 바꾼다. 선점이 DB를 읽으면 409 SEAT_NOT_SELLABLE이다
        jdbcTemplate.update("UPDATE p_schedule_seats SET is_sellable = false WHERE id = ?", seatId);

        SeatHoldResult hold = seatHoldService.hold(USER, SCHEDULE, seatId);

        assertThat(hold.holdId()).isNotNull();
        assertThat(errorCode(() -> conversionService.convert(USER, SCHEDULE, hold.holdId())))
                .isEqualTo("SEAT_NOT_SELLABLE");
    }

    @Test
    @DisplayName("좌석 고정 정보 캐시에 없는 좌석(다른 회차)은 404이고 판매 검증을 부르지 않는다")
    void fixedSeatCacheRejectsOtherSchedule() {
        seat();
        Long otherScheduleSeat = scheduleSeatRepository
                .save(ScheduleSeat.create(
                        SCHEDULE + 1, 100L, 7L, "VIP", FakeSaleValidationPort.DEFAULT_PRICE, "A", "1", 1, true, 2L))
                .getId();
        stateLoader.reload(SCHEDULE);

        assertThat(errorCode(() -> seatHoldService.hold(USER, SCHEDULE, otherScheduleSeat)))
                .isEqualTo("SEAT_NOT_FOUND");
        assertThat(saleValidation.calls()).isZero();
    }

    @Test
    @DisplayName("결제 중인 예약의 좌석은 선점 만료 뒤에도, 확정(sell) 뒤에도 Redis 점유에 남아 다른 회원의 선점이 409다")
    void paymentInProgressAndSoldStayReserved() {
        Long seatId = seat();
        stateLoader.reload(SCHEDULE);
        Long reservationId = convertedReservation(USER, seatId);
        jdbcTemplate.update(
                "UPDATE p_reservations SET status = 'PAYMENT_IN_PROGRESS' WHERE reservation_id = ?", reservationId);
        clock.advance(Duration.ofMinutes(11));

        // 결제 중인 예약은 만료 스케줄러가 정리하지 않는다. 좌석이 풀리지 않으니 Redis 점유도 남는다
        assertThat(holdExpiryService.expireDue(10, Duration.ofSeconds(5))).isZero();
        assertThat(reserved()).containsEntry(String.valueOf(seatId), String.valueOf(reservationId));
        assertThat(errorCode(() -> seatHoldService.hold(OTHER_USER, SCHEDULE, seatId)))
                .isEqualTo("SEAT_ALREADY_TAKEN");

        // 결제가 확정되면 좌석이 SOLD가 된다. sell은 Redis를 건드리지 않고, 정합 작업도 DB에 예약이 있어 지우지 않는다
        Integer sold = transactionTemplate.execute(status -> seatFacade.sell(List.of(seatId), reservationId));
        assertThat(sold).isEqualTo(1);
        assertThat(reconciler.reconcile(SCHEDULE)).isZero();
        assertThat(reserved()).containsEntry(String.valueOf(seatId), String.valueOf(reservationId));
        assertThat(errorCode(() -> seatHoldService.hold(OTHER_USER, SCHEDULE, seatId)))
                .isEqualTo("SEAT_ALREADY_TAKEN");
    }
}
