package com.sparta.ticket.reservation.booking.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.common.auth.UserContext;
import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.booking.application.dto.CheckoutCommand;
import com.sparta.ticket.reservation.booking.application.dto.ConfirmCommand;
import com.sparta.ticket.reservation.booking.application.payment.ReservationPaymentService;
import com.sparta.ticket.reservation.booking.application.port.ConfirmOutcome;
import com.sparta.ticket.reservation.booking.domain.ReservationStatus;
import com.sparta.ticket.reservation.booking.infrastructure.seat.SeatFacadeAdapter;
import com.sparta.ticket.reservation.booking.support.BookingIntegrationTest;
import com.sparta.ticket.reservation.booking.support.TestIds;
import com.sparta.ticket.reservation.seat.application.hold.SeatHoldConversionService;
import com.sparta.ticket.reservation.seat.application.hold.SeatHoldMode;
import com.sparta.ticket.reservation.seat.application.hold.SeatHoldResult;
import com.sparta.ticket.reservation.seat.application.hold.SeatHoldService;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeat;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeatRepository;
import com.sparta.ticket.reservation.seat.support.FakeSaleValidationPort;
import com.sparta.ticket.reservation.seat.support.MutableSeatHoldModeSwitch;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * REDIS 모드 선점(#449)을 예매 결제 흐름까지 이어서 확인한다. 결제하기로 만든 예약은 DB 방식 선점 예약과 같아서, 결제 실패 복귀·재결제·
 * 확정이 예매 코드 변경 없이 그대로 되어야 한다. 스위치를 바꾸는 사이에 두 방식이 섞일 때도 예약은 한 건만 생겨야 한다.
 */
class RedisSeatHoldPaymentIntegrationTest extends BookingIntegrationTest {

    @Autowired
    private SeatHoldService seatHoldService;

    @Autowired
    private SeatHoldConversionService conversionService;

    @Autowired
    private ReservationPaymentService paymentService;

    @Autowired
    private SeatFacadeAdapter seatFacadeAdapter;

    @Autowired
    private ScheduleSeatRepository scheduleSeatRepository;

    @Autowired
    private FakeSaleValidationPort saleValidation;

    @Autowired
    private MutableSeatHoldModeSwitch modeSwitch;

    @Autowired
    private StringRedisTemplate redis;

    private final UserContext user = new UserContext(TestIds.next(), "hong01", Role.USER);
    private final UserContext other = new UserContext(TestIds.next(), "kim02", Role.USER);
    private final Long scheduleId = TestIds.next();
    private long nextVenueSeatId = 1;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM p_schedule_seats");
        Set<String> keys = redis.keys("seathold:{s" + scheduleId + "}:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        seats.useReal(seatFacadeAdapter);
        saleValidation.reset();
        modeSwitch.set(SeatHoldMode.REDIS);
    }

    @AfterEach
    void tearDown() {
        modeSwitch.reset();
        seats.reset();
    }

    private Long seat() {
        long venueSeatId = nextVenueSeatId++;
        return scheduleSeatRepository
                .save(ScheduleSeat.create(
                        scheduleId,
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

    private ReservationStatus pay(UserContext payer, Long reservationId) {
        String orderId = paymentService
                .checkout(payer, reservationId, new CheckoutCommand(null, "TOSS"))
                .orderId();
        return paymentService
                .confirm(payer, reservationId, new ConfirmCommand(orderId, "pk-" + orderId))
                .status();
    }

    private String seatStatus(Long seatId) {
        return jdbcTemplate.queryForObject("SELECT status FROM p_schedule_seats WHERE id = ?", String.class, seatId);
    }

    private int reservations() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM p_reservations", Integer.class);
    }

    @Test
    @DisplayName("결제하기로 만든 예약은 결제 실패 뒤 HOLD로 돌아오고, 다시 결제하면 확정되어 좌석이 SOLD가 된다")
    void failThenRetry() {
        Long seatId = seat();
        String holdId = seatHoldService.hold(user.userId(), scheduleId, seatId).holdId();
        Long reservationId =
                conversionService.convert(user.userId(), scheduleId, holdId).reservationId();

        payment.confirmReturns(ConfirmOutcome.failed());
        assertThat(pay(user, reservationId)).isEqualTo(ReservationStatus.HOLD);
        assertThat(seatStatus(seatId)).isEqualTo("HOLD");

        payment.confirmReturns(ConfirmOutcome.succeeded());
        assertThat(pay(user, reservationId)).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(seatStatus(seatId)).isEqualTo("SOLD");
    }

    @Test
    @DisplayName("확정된 좌석은 Redis 키가 모두 사라져도 다른 회원이 선점할 수 없다")
    void soldSeatAfterKeysLost() {
        Long seatId = seat();
        String holdId = seatHoldService.hold(user.userId(), scheduleId, seatId).holdId();
        Long reservationId =
                conversionService.convert(user.userId(), scheduleId, holdId).reservationId();
        assertThat(pay(user, reservationId)).isEqualTo(ReservationStatus.CONFIRMED);
        redis.delete(redis.keys("seathold:{s" + scheduleId + "}:*"));

        assertThat(errorOf(() -> seatHoldService.hold(other.userId(), scheduleId, seatId)))
                .isEqualTo("SEAT_ALREADY_TAKEN");
    }

    @Test
    @DisplayName("REDIS에서 잡은 선점은 스위치를 DB로 돌린 뒤에도 결제하기로 예약을 만들 수 있다")
    void convertAfterSwitchOff() {
        Long seatId = seat();
        String holdId = seatHoldService.hold(user.userId(), scheduleId, seatId).holdId();
        modeSwitch.set(SeatHoldMode.DB);

        SeatHoldResult result = conversionService.convert(user.userId(), scheduleId, holdId);

        assertThat(result.created()).isTrue();
        assertThat(seatStatus(seatId)).isEqualTo("HOLD");
    }

    @Test
    @DisplayName("스위치를 DB로 돌린 사이 같은 좌석을 DB 방식으로 잡으면, 늦은 결제하기는 409이고 예약은 한 건이다")
    void mixedModesSameSeat() {
        Long seatId = seat();
        String holdId = seatHoldService.hold(user.userId(), scheduleId, seatId).holdId();
        modeSwitch.set(SeatHoldMode.DB);
        Long dbReservation =
                seatHoldService.hold(other.userId(), scheduleId, seatId).reservationId();

        assertThat(errorOf(() -> conversionService.convert(user.userId(), scheduleId, holdId)))
                .isEqualTo("SEAT_ALREADY_TAKEN");
        assertThat(reservations()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT reservation_id FROM p_schedule_seats WHERE id = ?", Long.class, seatId))
                .isEqualTo(dbReservation);
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
