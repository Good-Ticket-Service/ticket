package com.sparta.ticket.reservation.seat.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.booking.support.MutableClock;
import com.sparta.ticket.reservation.seat.application.hold.SeatHoldMode;
import com.sparta.ticket.reservation.seat.application.hold.SeatHoldResult;
import com.sparta.ticket.reservation.seat.application.hold.SeatHoldService;
import com.sparta.ticket.reservation.seat.application.release.SeatReleaseService;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeat;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeatRepository;
import com.sparta.ticket.reservation.seat.support.FakeSaleValidationPort;
import com.sparta.ticket.reservation.seat.support.MutableSeatHoldModeSwitch;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * REDIS 모드 선점(#449)을 실제 DB·Redis로 확인한다. 선점은 Redis에만 남고 DB에는 쓰지 않는다.
 * 예약으로 바꾼 회원은 회원 키가 "CONVERTED:예약ID"다. 결제하기(3단계)가 아직 없어, DB 모드로 예약을 만들고 회원 키를 직접 써서 같은 상태를 만든다.
 */
class RedisSeatHoldIntegrationTest extends ReservationIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 20, 0);
    private static final Long SCHEDULE = 702L;
    private static final Long USER = 1L;
    private static final Long OTHER_USER = 2L;

    @Autowired
    private SeatHoldService seatHoldService;

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

    private long nextVenueSeatId = 1;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM p_schedule_seats");
        jdbcTemplate.update("DELETE FROM p_tickets");
        jdbcTemplate.update("DELETE FROM p_reservations");
        Set<String> keys = redis.keys("seathold:{s" + SCHEDULE + "}:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        saleValidation.reset();
        clock.set(NOW);
        modeSwitch.set(SeatHoldMode.REDIS);
    }

    @AfterEach
    void tearDown() {
        modeSwitch.reset();
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

    /** 결제하기로 예약을 만든 뒤의 회원 키. */
    private void markConverted(Long userId, Long reservationId) {
        redis.opsForValue()
                .set("seathold:{s" + SCHEDULE + "}:user:" + userId, "CONVERTED:" + reservationId, Duration.ofHours(1));
    }

    private String errorCode(Runnable action) {
        try {
            action.run();
        } catch (BusinessException e) {
            return e.getErrorCode().code();
        }
        throw new AssertionError("오류가 나지 않았다");
    }

    private int reservations() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM p_reservations", Integer.class);
    }

    @Test
    @DisplayName("Redis에만 기록하고 holdId를 돌려준다. DB에는 예약을 만들지 않고 좌석도 AVAILABLE이다")
    void createsRedisHoldOnly() {
        Long seatId = seat();

        SeatHoldResult result = seatHoldService.hold(USER, SCHEDULE, seatId);

        assertThat(result.created()).isTrue();
        assertThat(result.holdId()).startsWith("h_").hasSize(34);
        assertThat(result.reservationId()).isNull();
        assertThat(result.holdExpiresAt()).isEqualTo(NOW.plusMinutes(10));
        assertThat(result.basePrice()).isEqualTo(FakeSaleValidationPort.DEFAULT_PRICE);
        assertThat(reservations()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM p_schedule_seats WHERE id = ?", String.class, seatId))
                .isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("같은 회원이 같은 좌석을 다시 누르면 같은 holdId와 처음 만료 시각으로 200이다")
    void sameUserSameSeat() {
        Long seatId = seat();
        SeatHoldResult first = seatHoldService.hold(USER, SCHEDULE, seatId);
        clock.advance(Duration.ofMinutes(1));

        SeatHoldResult again = seatHoldService.hold(USER, SCHEDULE, seatId);

        assertThat(again.created()).isFalse();
        assertThat(again.holdId()).isEqualTo(first.holdId());
        assertThat(again.holdExpiresAt()).isEqualTo(first.holdExpiresAt());
    }

    @Test
    @DisplayName("남이 잡은 좌석은 409 SEAT_ALREADY_TAKEN, 다른 좌석을 잡은 회원은 409 PURCHASE_LIMIT_EXCEEDED다")
    void takenAndLimit() {
        Long seatId = seat();
        Long otherSeatId = seat();
        seatHoldService.hold(USER, SCHEDULE, seatId);

        assertThat(errorCode(() -> seatHoldService.hold(OTHER_USER, SCHEDULE, seatId)))
                .isEqualTo("SEAT_ALREADY_TAKEN");
        assertThat(errorCode(() -> seatHoldService.hold(USER, SCHEDULE, otherSeatId)))
                .isEqualTo("PURCHASE_LIMIT_EXCEEDED");
    }

    @Test
    @DisplayName("DB에서 남이 만료 전 HOLD로 잡았거나 팔린 좌석은 Redis에 기록하지 않고 409다")
    void heldOrSoldInDb() {
        Long heldSeat = seat();
        Long soldSeat = seat();
        dbHold(OTHER_USER, heldSeat);
        Long soldReservation = dbHold(3L, soldSeat);
        jdbcTemplate.update(
                "UPDATE p_schedule_seats SET status = 'SOLD', hold_expires_at = NULL WHERE reservation_id = ?",
                soldReservation);

        assertThat(errorCode(() -> seatHoldService.hold(USER, SCHEDULE, heldSeat)))
                .isEqualTo("SEAT_ALREADY_TAKEN");
        assertThat(errorCode(() -> seatHoldService.hold(USER, SCHEDULE, soldSeat)))
                .isEqualTo("SEAT_ALREADY_TAKEN");
        assertThat(redis.hasKey("seathold:{s" + SCHEDULE + "}:user:" + USER)).isFalse();
    }

    @Test
    @DisplayName("이미 예약으로 바꾼 좌석을 그 회원이 다시 누르면 그 예약을 200으로 돌려준다")
    void convertedSeatReturnsReservation() {
        Long seatId = seat();
        Long reservationId = dbHold(USER, seatId);
        markConverted(USER, reservationId);

        SeatHoldResult again = seatHoldService.hold(USER, SCHEDULE, seatId);

        assertThat(again.created()).isFalse();
        assertThat(again.reservationId()).isEqualTo(reservationId);
        assertThat(again.holdId()).isNull();
        assertThat(again.holdExpiresAt()).isEqualTo(NOW.plusMinutes(10));
    }

    @Test
    @DisplayName("예약으로 바꾼 회원이 만료 전 예약을 두고 다른 좌석을 누르면 DB를 확인해 409 PURCHASE_LIMIT_EXCEEDED다")
    void convertedUserWithActiveReservation() {
        Long seatId = seat();
        Long otherSeatId = seat();
        markConverted(USER, dbHold(USER, seatId));

        assertThat(errorCode(() -> seatHoldService.hold(USER, SCHEDULE, otherSeatId)))
                .isEqualTo("PURCHASE_LIMIT_EXCEEDED");
        assertThat(redis.hasKey("seathold:{s" + SCHEDULE + "}:seat:" + otherSeatId))
                .isFalse();
    }

    @Test
    @DisplayName("예약으로 바꾼 회원의 예약이 만료 시각을 지났으면 정리 전이어도 막지 않고 Redis에 기록한다")
    void convertedUserWithExpiredReservation() {
        Long seatId = seat();
        Long otherSeatId = seat();
        markConverted(USER, dbHold(USER, seatId));
        clock.advance(Duration.ofMinutes(11));

        SeatHoldResult result = seatHoldService.hold(USER, SCHEDULE, otherSeatId);

        assertThat(result.created()).isTrue();
        assertThat(result.holdId()).isNotNull();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM p_reservations", String.class))
                .isEqualTo("HOLD");
        assertThat(redis.opsForValue().get("seathold:{s" + SCHEDULE + "}:user:" + USER))
                .isEqualTo(result.holdId());
    }

    @Test
    @DisplayName("예약으로 바꾼 회원의 예약이 이미 끝났으면(해제) 회원 표시를 지우고 기록한다")
    void convertedUserWithClosedReservation() {
        Long seatId = seat();
        Long otherSeatId = seat();
        Long reservationId = dbHold(USER, seatId);
        markConverted(USER, reservationId);
        seatReleaseService.release(USER, SCHEDULE, reservationId);

        SeatHoldResult result = seatHoldService.hold(USER, SCHEDULE, otherSeatId);

        assertThat(result.created()).isTrue();
        assertThat(reservations()).isEqualTo(1);
    }

    @Test
    @DisplayName("스위치가 DB면 지금처럼 예약을 만들고 holdId는 없다")
    void dbModeUnchanged() {
        Long seatId = seat();
        modeSwitch.set(SeatHoldMode.DB);

        SeatHoldResult result = seatHoldService.hold(USER, SCHEDULE, seatId);

        assertThat(result.reservationId()).isNotNull();
        assertThat(result.holdId()).isNull();
        assertThat(reservations()).isEqualTo(1);
        assertThat(redis.hasKey("seathold:{s" + SCHEDULE + "}:user:" + USER)).isFalse();
    }

    @Test
    @DisplayName("판매 검증 가격이 좌석과 다르면 Redis에 기록하지 않고 409 SEAT_CONFIG_VERSION_MISMATCH다")
    void priceMismatch() {
        Long seatId = seat();
        saleValidation.price(FakeSaleValidationPort.DEFAULT_PRICE + 1);

        assertThatThrownBy(() -> seatHoldService.hold(USER, SCHEDULE, seatId))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode().code())
                .isEqualTo("SEAT_CONFIG_VERSION_MISMATCH");
        assertThat(redis.hasKey("seathold:{s" + SCHEDULE + "}:seat:" + seatId)).isFalse();
    }
}
