package com.sparta.ticket.reservation.booking.application.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.common.exception.ErrorCode;
import com.sparta.ticket.reservation.booking.application.ChangeStamps;
import com.sparta.ticket.reservation.booking.application.dto.ConfirmCommand;
import com.sparta.ticket.reservation.booking.application.payment.PaymentRequestTransactions.PaymentStart;
import com.sparta.ticket.reservation.booking.application.port.CouponPort;
import com.sparta.ticket.reservation.booking.domain.ChangeStamp;
import com.sparta.ticket.reservation.booking.domain.Reservation;
import com.sparta.ticket.reservation.booking.domain.ReservationRepository;
import com.sparta.ticket.reservation.booking.domain.ReservationStatus;
import com.sparta.ticket.reservation.booking.domain.SaleTarget;
import com.sparta.ticket.reservation.booking.domain.SeatPrice;
import com.sparta.ticket.reservation.domain.ReservationErrorCode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * startPayment가 0행일 때 다시 읽은 예약으로 어떤 오류나 결과를 돌려주는지 확인한다. 오류 코드의 우선순위
 * (본인 아님 → 다른 시도 → 상태)는 통합 테스트로 한 번에 만들기 어려워 여기서 본다.
 */
class PaymentRequestTransactionsTest {

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final Long RESERVATION_ID = 10L;
    private static final String ORDER_ID = "order-1";
    private static final ConfirmCommand COMMAND = new ConfirmCommand(ORDER_ID, "pg-token");
    private static final ChangeStamp STAMP = new ChangeStamp(LocalDateTime.of(2026, 10, 29, 12, 0), "1");

    private final ReservationRepository reservationRepository = mock(ReservationRepository.class);
    private final ChangeStamps changeStamps = mock(ChangeStamps.class);
    private final PaymentRequestTransactions transactions =
            new PaymentRequestTransactions(reservationRepository, changeStamps, mock(CouponPort.class));

    @BeforeEach
    void setUp() {
        when(changeStamps.stamp()).thenReturn(STAMP);
    }

    @Test
    @DisplayName("전이가 1행이면 started이고 다시 읽은 결제 금액을 돌려준다")
    void startedReturnsReloadedAmount() {
        givenStarted(true);
        Reservation reloaded = givenReloaded(ReservationStatus.PAYMENT_IN_PROGRESS, true, true, false);
        ReflectionTestUtils.setField(reloaded, "paymentAmount", 20_000L);

        PaymentStart start = transactions.startPayment(USER_ID, RESERVATION_ID, COMMAND);

        assertThat(start.started()).isTrue();
        assertThat(start.amount()).isEqualTo(20_000L);
    }

    @ParameterizedTest
    @EnumSource(
            value = ReservationStatus.class,
            names = {"PAYMENT_IN_PROGRESS", "CONFIRMED"})
    @DisplayName("0행이고 같은 시도가 이미 결제 중이거나 확정이면 오류 대신 지금 상태를 돌려준다")
    void sameAttemptReturnsCurrentState(ReservationStatus status) {
        givenStarted(false);
        givenReloaded(status, true, true, false);

        PaymentStart start = transactions.startPayment(USER_ID, RESERVATION_ID, COMMAND);

        assertThat(start.started()).isFalse();
        assertThat(start.amount()).isZero();
        assertThat(start.current().status()).isEqualTo(status);
    }

    @Test
    @DisplayName("0행이고 EXPIRED면 HOLD_EXPIRED다")
    void expiredIsHoldExpired() {
        givenStarted(false);
        givenReloaded(ReservationStatus.EXPIRED, true, true, true);

        assertError(ReservationErrorCode.HOLD_EXPIRED);
    }

    @Test
    @DisplayName("0행이고 HOLD 그대로면 시간이 지났을 때 HOLD_EXPIRED, 남았을 때 INVALID_STATUS_TRANSITION이다")
    void holdDependsOnExpiry() {
        givenStarted(false);
        givenReloaded(ReservationStatus.HOLD, true, true, true);
        assertError(ReservationErrorCode.HOLD_EXPIRED);

        givenReloaded(ReservationStatus.HOLD, true, true, false);
        assertError(CommonErrorCode.INVALID_STATUS_TRANSITION);
    }

    @ParameterizedTest
    @EnumSource(
            value = ReservationStatus.class,
            names = {"RELEASED", "CANCELED"})
    @DisplayName("0행이고 해제·취소된 예약이면 INVALID_STATUS_TRANSITION이다")
    void closedIsInvalidTransition(ReservationStatus status) {
        givenStarted(false);
        givenReloaded(status, true, true, false);

        assertError(CommonErrorCode.INVALID_STATUS_TRANSITION);
    }

    @Test
    @DisplayName("0행이고 다른 시도의 orderId면 상태와 상관없이 ORDER_ID_MISMATCH다")
    void otherAttemptIsMismatch() {
        givenStarted(false);
        givenReloaded(ReservationStatus.CONFIRMED, true, false, false);

        assertError(ReservationErrorCode.ORDER_ID_MISMATCH);
    }

    @Test
    @DisplayName("0행이고 남의 예약이면 orderId가 달라도 FORBIDDEN이 먼저다")
    void otherUserIsForbiddenFirst() {
        givenStarted(false);
        givenReloaded(ReservationStatus.EXPIRED, false, false, true);

        assertError(CommonErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("다시 읽을 예약이 없으면 RESERVATION_NOT_FOUND다")
    void missingReservation() {
        givenStarted(false);
        when(reservationRepository.reload(RESERVATION_ID)).thenReturn(Optional.empty());

        assertError(ReservationErrorCode.RESERVATION_NOT_FOUND);
    }

    private void givenStarted(boolean started) {
        when(reservationRepository.startPayment(eq(RESERVATION_ID), eq(USER_ID), eq(ORDER_ID), any(), eq(STAMP)))
                .thenReturn(started ? 1 : 0);
    }

    /** 본인 여부, 같은 시도, 만료는 Reservation이 판단하므로 mock 대신 그 값을 넣은 실제 예약을 쓴다. */
    private Reservation givenReloaded(ReservationStatus status, boolean owned, boolean sameAttempt, boolean expired) {
        Reservation reservation = Reservation.hold(
                owned ? USER_ID : OTHER_USER_ID,
                new SaleTarget(1L, 2L, 3L),
                List.of(new SeatPrice(100L, 10_000)),
                expired ? STAMP.at().minusMinutes(1) : STAMP.at().plusMinutes(10));
        ReflectionTestUtils.setField(reservation, "id", RESERVATION_ID);
        ReflectionTestUtils.setField(reservation, "status", status);
        ReflectionTestUtils.setField(reservation, "orderId", sameAttempt ? ORDER_ID : "order-other");
        when(reservationRepository.reload(RESERVATION_ID)).thenReturn(Optional.of(reservation));
        return reservation;
    }

    private void assertError(ErrorCode expected) {
        assertThatThrownBy(() -> transactions.startPayment(USER_ID, RESERVATION_ID, COMMAND))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }
}
