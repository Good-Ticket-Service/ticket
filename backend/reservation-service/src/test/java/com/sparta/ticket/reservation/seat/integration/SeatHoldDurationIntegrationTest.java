package com.sparta.ticket.reservation.seat.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.common.auth.UserContext;
import com.sparta.ticket.reservation.booking.application.dto.CheckoutCommand;
import com.sparta.ticket.reservation.booking.application.dto.ConfirmCommand;
import com.sparta.ticket.reservation.booking.application.expiry.HoldExpiryService;
import com.sparta.ticket.reservation.booking.application.payment.ReservationPaymentService;
import com.sparta.ticket.reservation.booking.infrastructure.seat.SeatFacadeAdapter;
import com.sparta.ticket.reservation.booking.support.BookingIntegrationTest;
import com.sparta.ticket.reservation.booking.support.TestIds;
import com.sparta.ticket.reservation.seat.application.hold.SeatHoldResult;
import com.sparta.ticket.reservation.seat.application.hold.SeatHoldService;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeat;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeatRepository;
import com.sparta.ticket.reservation.seat.support.FakeSaleValidationPort;
import com.sparta.ticket.reservation.seat.support.MutableSeatHoldDuration;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 선점 유지 시간 설정(#248). 바꾼 유지 시간이 좌석·예약의 holdExpiresAt과 결제 준비의 expiresAt에 같은 값으로 들어가고,
 * 짧게 해도 결제 중 예약은 만료 스케줄러가 건드리지 않는지 본다. 기본값 10분은 SeatHoldApiIntegrationTest가 확인한다.
 */
class SeatHoldDurationIntegrationTest extends BookingIntegrationTest {

    private static final Long SCHEDULE = 701L;

    @Autowired
    private SeatHoldService seatHoldService;

    @Autowired
    private ScheduleSeatRepository scheduleSeatRepository;

    @Autowired
    private FakeSaleValidationPort saleValidation;

    @Autowired
    private MutableSeatHoldDuration holdDuration;

    @Autowired
    private ReservationPaymentService paymentService;

    @Autowired
    private HoldExpiryService holdExpiryService;

    @Autowired
    private SeatFacadeAdapter seatFacadeAdapter;

    private final UserContext user = new UserContext(TestIds.next(), "hong01", Role.USER);

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM p_schedule_seats");
        saleValidation.reset();
        seats.useReal(seatFacadeAdapter);
    }

    @AfterEach
    void tearDown() {
        // 컨텍스트를 다른 테스트와 함께 쓰므로 유지 시간과 실제 좌석 연결을 되돌린다
        holdDuration.reset();
        seats.reset();
    }

    /** 판매 검증 가짜의 기본 가격, 구성 버전 2로 좌석을 만든다. */
    private Long seat() {
        return scheduleSeatRepository
                .save(ScheduleSeat.create(
                        SCHEDULE, 1L, 7L, "VIP", FakeSaleValidationPort.DEFAULT_PRICE, "A", "1", 1, true, 2L))
                .getId();
    }

    private Map<String, Object> seatRow(Long seatId) {
        return jdbcTemplate.queryForMap(
                "SELECT status, reservation_id, hold_expires_at FROM p_schedule_seats WHERE id = ?", seatId);
    }

    private LocalDateTime reservationExpiresAt(Long reservationId) {
        return jdbcTemplate
                .queryForObject(
                        "SELECT hold_expires_at FROM p_reservations WHERE reservation_id = ?",
                        Timestamp.class,
                        reservationId)
                .toLocalDateTime();
    }

    @Test
    @DisplayName("유지 시간을 바꾸면 좌석·예약의 만료 시각과 결제 준비의 만료 시각이 모두 바꾼 값이 된다")
    void changedDurationFlowsToSeatReservationAndPayment() {
        holdDuration.set(Duration.ofMinutes(3));
        Long seatId = seat();
        LocalDateTime expected = clock.now().plusMinutes(3);

        SeatHoldResult result = seatHoldService.hold(user.userId(), SCHEDULE, seatId);
        paymentService.checkout(user, result.reservationId(), new CheckoutCommand(null, "TOSS"));

        assertThat(result.holdExpiresAt()).isEqualTo(expected);
        assertThat(((Timestamp) seatRow(seatId).get("hold_expires_at")).toLocalDateTime())
                .isEqualTo(expected);
        assertThat(reservationExpiresAt(result.reservationId())).isEqualTo(expected);
        assertThat(payment.prepared)
                .singleElement()
                .satisfies(request -> assertThat(request.expiresAt()).isEqualTo(expected));
    }

    @Test
    @DisplayName("유지 시간이 짧아도 결제 중 예약은 만료 시각이 지나도 만료 스케줄러가 건드리지 않는다")
    void shortDurationDoesNotExpirePaymentInProgress() {
        holdDuration.set(Duration.ofMinutes(1));
        Long seatId = seat();
        SeatHoldResult result = seatHoldService.hold(user.userId(), SCHEDULE, seatId);
        String orderId = paymentService
                .checkout(user, result.reservationId(), new CheckoutCommand(null, "TOSS"))
                .orderId();
        // 승인 결과를 모르는 채로 결제 중이 된다
        payment.confirmTimesOut();
        paymentService.confirm(user, result.reservationId(), new ConfirmCommand(orderId, "pk-1"));
        assertThat(statusOf(result.reservationId())).isEqualTo("PAYMENT_IN_PROGRESS");

        clock.advance(Duration.ofMinutes(2));
        int expired = holdExpiryService.expireDue(10, Duration.ofSeconds(5));

        assertThat(expired).isZero();
        assertThat(statusOf(result.reservationId())).isEqualTo("PAYMENT_IN_PROGRESS");
        Map<String, Object> row = seatRow(seatId);
        assertThat(row.get("status")).isEqualTo("HOLD");
        assertThat(((Number) row.get("reservation_id")).longValue()).isEqualTo(result.reservationId());
    }
}
