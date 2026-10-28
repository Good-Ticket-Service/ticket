package com.sparta.ticket.reservation.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.common.exception.ErrorCode;
import com.sparta.ticket.reservation.booking.application.PaymentRequestTransactions.PaymentStart;
import com.sparta.ticket.reservation.booking.application.dto.ConfirmCommand;
import com.sparta.ticket.reservation.booking.domain.ChangeStamp;
import com.sparta.ticket.reservation.booking.domain.Reservation;
import com.sparta.ticket.reservation.booking.domain.ReservationRepository;
import com.sparta.ticket.reservation.booking.domain.ReservationStatus;
import com.sparta.ticket.reservation.booking.domain.ReservationTransition;
import com.sparta.ticket.reservation.domain.ReservationErrorCode;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;

/**
 * startPayment가 0행일 때 다시 읽은 예약으로 이유를 가리는 순서를 확인한다. 오류 코드의 우선순위
 * (본인 아님 → 다른 시도 → 상태)는 통합 테스트로 한 번에 만들기 어려워 여기서 본다.
 */
class PaymentRequestTransactionsTest {

    private static final Long USER_ID = 1L;
    private static final Long RESERVATION_ID = 10L;
    private static final String ORDER_ID = "order-1";
    private static final ConfirmCommand COMMAND = new ConfirmCommand(ORDER_ID, "pg-token");
    private static final ChangeStamp STAMP = new ChangeStamp(LocalDateTime.of(2026, 10, 29, 12, 0), "1");

    private final ReservationRepository reservationRepository = mock(ReservationRepository.class);
    private final ReservationTransitionExecutor transitionExecutor = mock(ReservationTransitionExecutor.class);
    private final ChangeStamps changeStamps = mock(ChangeStamps.class);
    private final PaymentRequestTransactions transactions =
            new PaymentRequestTransactions(reservationRepository, transitionExecutor, changeStamps);

    @BeforeEach
    void setUp() {
        when(changeStamps.stamp()).thenReturn(STAMP);
    }

    @Test
    @DisplayName("전이가 1행이면 started이고 다시 읽은 결제 금액을 돌려준다")
    void startedReturnsReloadedAmount() {
        givenStarted(true);
        Reservation reloaded = givenReloaded(ReservationStatus.PAYMENT_IN_PROGRESS, true, true, false);
        when(reloaded.getPaymentAmount()).thenReturn(20_000L);

        PaymentStart start = transactions.startPayment(USER_ID, RESERVATION_ID, COMMAND);

        assertThat(start.started()).isTrue();
        assertThat(start.amount()).isEqualTo(20_000L);
        InOrder order = inOrder(transitionExecutor, reservationRepository);
        order.verify(transitionExecutor)
                .execute(eq(ReservationTransition.START_PAYMENT), eq(RESERVATION_ID), anyList(), any());
        order.verify(reservationRepository).reload(RESERVATION_ID);
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
        when(transitionExecutor.execute(
                        eq(ReservationTransition.START_PAYMENT), eq(RESERVATION_ID), anyList(), any(IntSupplier.class)))
                .thenReturn(started);
    }

    private Reservation givenReloaded(ReservationStatus status, boolean owned, boolean sameAttempt, boolean expired) {
        Reservation reservation = mock(Reservation.class);
        when(reservation.getStatus()).thenReturn(status);
        when(reservation.isOwnedBy(USER_ID)).thenReturn(owned);
        when(reservation.isAttempt(ORDER_ID)).thenReturn(sameAttempt);
        when(reservation.isHoldExpiredAt(STAMP.at())).thenReturn(expired);
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
