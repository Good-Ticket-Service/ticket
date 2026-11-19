package com.sparta.ticket.reservation.seat.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.booking.support.MutableClock;
import com.sparta.ticket.reservation.seat.application.hold.SeatHoldConversionService;
import com.sparta.ticket.reservation.seat.application.hold.SeatHoldMode;
import com.sparta.ticket.reservation.seat.application.hold.SeatHoldResult;
import com.sparta.ticket.reservation.seat.application.hold.SeatHoldService;
import com.sparta.ticket.reservation.seat.application.release.SeatReleaseService;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeat;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeatRepository;
import com.sparta.ticket.reservation.seat.support.FakeSaleValidationPort;
import com.sparta.ticket.reservation.seat.support.MutableSeatHoldModeSwitch;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 결제하기 때 Redis 선점을 DB 예약으로 바꾸는 것(#449)을 실제 DB·Redis로 확인한다.
 * 최종 판정은 DB 좌석 행 잠금이라, 같은 선점을 동시에 바꾸거나 Redis 키가 사라져도 예약은 한 건만 생겨야 한다.
 */
class SeatHoldConversionIntegrationTest extends ReservationIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 20, 0);
    private static final Long SCHEDULE = 703L;
    private static final Long USER = 1L;
    private static final Long OTHER_USER = 2L;
    private static final int RACERS = 8;
    private static final int ROUNDS = 20;

    @Autowired
    private SeatHoldService seatHoldService;

    @Autowired
    private SeatHoldConversionService conversionService;

    @Autowired
    private SeatReleaseService seatReleaseService;

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

    private final ExecutorService pool = Executors.newFixedThreadPool(RACERS);
    private long nextVenueSeatId = 1;

    @BeforeEach
    void setUp() {
        cleanUp();
        saleValidation.reset();
        clock.set(NOW);
        modeSwitch.set(SeatHoldMode.REDIS);
    }

    @AfterEach
    void tearDown() {
        modeSwitch.reset();
        pool.shutdownNow();
    }

    private void cleanUp() {
        jdbcTemplate.update("DELETE FROM p_schedule_seats");
        jdbcTemplate.update("DELETE FROM p_tickets");
        jdbcTemplate.update("DELETE FROM p_reservations");
        Set<String> keys = redis.keys("seathold:{s" + SCHEDULE + "}:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
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

    private String key(String suffix) {
        return "seathold:{s" + SCHEDULE + "}:" + suffix;
    }

    private String outcome(Callable<SeatHoldResult> action) throws Exception {
        try {
            SeatHoldResult result = action.call();
            return result.created() ? "CREATED" : "EXISTING";
        } catch (BusinessException e) {
            return e.getErrorCode().code();
        }
    }

    private int count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    @Test
    @DisplayName("바꾸면 예약이 한 건 생기고, 만료 시각은 Redis 선점 때의 값이다. 좌석 키는 지우고 회원 키는 CONVERTED로 바꾼다")
    void converts() {
        Long seatId = seat();
        SeatHoldResult hold = seatHoldService.hold(USER, SCHEDULE, seatId);
        clock.advance(Duration.ofMinutes(2));

        SeatHoldResult result = conversionService.convert(USER, SCHEDULE, hold.holdId());

        assertThat(result.created()).isTrue();
        assertThat(result.reservationId()).isNotNull();
        assertThat(result.holdExpiresAt()).isEqualTo(NOW.plusMinutes(10));
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT hold_expires_at FROM p_schedule_seats WHERE id = ?", Timestamp.class, seatId))
                .isEqualTo(Timestamp.valueOf(NOW.plusMinutes(10)));
        assertThat(count("SELECT COUNT(*) FROM p_reservations WHERE user_id = ? AND status = 'HOLD'", USER))
                .isEqualTo(1);
        assertThat(redis.hasKey(key("seat:" + seatId))).isFalse();
        assertThat(redis.opsForValue().get(key("user:" + USER))).isEqualTo("CONVERTED:" + result.reservationId());
        // 회차 시작(11/30 19:00)까지 둔다. 테스트 시계는 11/1 20:02라 약 29일이다
        assertThat(redis.getExpire(key("user:" + USER), TimeUnit.DAYS)).isBetween(28L, 29L);
    }

    @Test
    @DisplayName("이미 바꾼 선점을 다시 바꾸면 같은 예약으로 200이다")
    void convertAgain() {
        Long seatId = seat();
        SeatHoldResult hold = seatHoldService.hold(USER, SCHEDULE, seatId);
        Long first = conversionService.convert(USER, SCHEDULE, hold.holdId()).reservationId();

        SeatHoldResult again = conversionService.convert(USER, SCHEDULE, hold.holdId());

        assertThat(again.created()).isFalse();
        assertThat(again.reservationId()).isEqualTo(first);
        assertThat(count("SELECT COUNT(*) FROM p_reservations")).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 선점을 8번 동시에 바꿔도 예약은 한 건이다(20번 반복)")
    void concurrentConvert() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            cleanUp();
            Long seatId = seat();
            String holdId = seatHoldService.hold(USER, SCHEDULE, seatId).holdId();
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < RACERS; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return outcome(() -> conversionService.convert(USER, SCHEDULE, holdId));
                }));
            }
            start.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }

            assertThat(results).containsOnly("CREATED", "EXISTING");
            assertThat(results).filteredOn("CREATED"::equals).hasSize(1);
            assertThat(count("SELECT COUNT(*) FROM p_reservations")).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM p_tickets")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("Redis 키가 사라져 두 회원이 같은 좌석을 잡아도, 둘 다 바꾸면 예약은 한 건이고 진 쪽 선점은 지운다")
    void lostKeyTwoHolders() {
        Long seatId = seat();
        SeatHoldResult first = seatHoldService.hold(USER, SCHEDULE, seatId);
        redis.delete(key("seat:" + seatId));
        SeatHoldResult second = seatHoldService.hold(OTHER_USER, SCHEDULE, seatId);
        assertThat(second.created()).isTrue();

        Long reservationId =
                conversionService.convert(USER, SCHEDULE, first.holdId()).reservationId();
        String loser = errorOf(() -> conversionService.convert(OTHER_USER, SCHEDULE, second.holdId()));

        assertThat(loser).isEqualTo("SEAT_ALREADY_TAKEN");
        assertThat(count("SELECT COUNT(*) FROM p_reservations")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM p_schedule_seats WHERE reservation_id = ?", reservationId))
                .isEqualTo(1);
        assertThat(redis.hasKey(key("hold:" + second.holdId()))).isFalse();
        assertThat(redis.hasKey(key("user:" + OTHER_USER))).isFalse();
    }

    @Test
    @DisplayName("남은 시간이 3초보다 적으면 409 HOLD_EXPIRED이고 예약을 만들지 않는다")
    void tooLate() {
        Long seatId = seat();
        SeatHoldResult hold = seatHoldService.hold(USER, SCHEDULE, seatId);
        clock.advance(Duration.ofMinutes(10).minusSeconds(2));

        assertThat(errorOf(() -> conversionService.convert(USER, SCHEDULE, hold.holdId())))
                .isEqualTo("HOLD_EXPIRED");
        assertThat(count("SELECT COUNT(*) FROM p_reservations")).isZero();
    }

    @Test
    @DisplayName("없는 선점과 남의 선점은 모두 404 HOLD_NOT_FOUND다")
    void notFound() {
        Long seatId = seat();
        SeatHoldResult hold = seatHoldService.hold(USER, SCHEDULE, seatId);

        assertThat(errorOf(() -> conversionService.convert(USER, SCHEDULE, "h_none")))
                .isEqualTo("HOLD_NOT_FOUND");
        assertThat(errorOf(() -> conversionService.convert(OTHER_USER, SCHEDULE, hold.holdId())))
                .isEqualTo("HOLD_NOT_FOUND");
    }

    @Test
    @DisplayName("바꾼 뒤 해제하면 다른 회원이 바로 그 좌석을 잡을 수 있다")
    void releaseAfterConvert() {
        Long seatId = seat();
        SeatHoldResult hold = seatHoldService.hold(USER, SCHEDULE, seatId);
        Long reservationId =
                conversionService.convert(USER, SCHEDULE, hold.holdId()).reservationId();

        seatReleaseService.release(USER, SCHEDULE, reservationId);
        SeatHoldResult other = seatHoldService.hold(OTHER_USER, SCHEDULE, seatId);

        assertThat(other.created()).isTrue();
        assertThat(other.holdId()).isNotNull();
    }

    @Test
    @DisplayName("판매 검증 뒤 좌석 구성이 바뀌었으면 409 SEAT_CONFIG_VERSION_MISMATCH이고 선점을 지운다")
    void configChangedBeforeConvert() {
        Long seatId = seat();
        SeatHoldResult hold = seatHoldService.hold(USER, SCHEDULE, seatId);
        jdbcTemplate.update("UPDATE p_schedule_seats SET config_version = 3 WHERE id = ?", seatId);

        assertThat(errorOf(() -> conversionService.convert(USER, SCHEDULE, hold.holdId())))
                .isEqualTo("SEAT_CONFIG_VERSION_MISMATCH");
        assertThat(count("SELECT COUNT(*) FROM p_reservations")).isZero();
        assertThat(redis.hasKey(key("hold:" + hold.holdId()))).isFalse();
    }

    private String errorOf(Runnable action) {
        try {
            action.run();
        } catch (BusinessException e) {
            return e.getErrorCode().code();
        }
        throw new AssertionError("오류가 나지 않았다");
    }
}
