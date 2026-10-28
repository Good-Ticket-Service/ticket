package com.sparta.ticket.reservation.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sparta.ticket.reservation.booking.application.port.ConfirmOutcome;
import com.sparta.ticket.reservation.booking.domain.ChangeStamp;
import com.sparta.ticket.reservation.booking.domain.Reservation;
import com.sparta.ticket.reservation.booking.domain.ReservationRepository;
import com.sparta.ticket.reservation.booking.domain.ReservationStatus;
import com.sparta.ticket.reservation.booking.domain.ReservationTransition;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * 결제 성공 반영의 갈래마다 전이, 다시 읽기, 환불 대상 표시, 메트릭을 어떤 순서로 몇 번 부르는지 본다.
 * 상태가 실제로 바뀌는지는 ReservationPaymentIntegrationTest와 BookingSeatIntegrationTest가 본다.
 */
class PaymentResultApplierTest {

    private static final Long RESERVATION_ID = 10L;
    private static final String ORDER_ID = "order-1";
    private static final List<Long> SEAT_IDS = List.of(100L, 101L);
    private static final ChangeStamp STAMP = new ChangeStamp(LocalDateTime.of(2026, 10, 29, 12, 0), "SYSTEM");

    private final ReservationRepository reservationRepository = mock(ReservationRepository.class);
    private final ReservationTransitionExecutor transitionExecutor = mock(ReservationTransitionExecutor.class);
    private final ChangeStamps changeStamps = mock(ChangeStamps.class);
    private final BookingMetrics metrics = mock(BookingMetrics.class);
    private final PaymentResultApplier applier =
            new PaymentResultApplier(reservationRepository, transitionExecutor, changeStamps, metrics);

    @BeforeEach
    void setUp() {
        when(changeStamps.stamp()).thenReturn(STAMP);
    }

    @Test
    @DisplayName("지금 시도의 결제 중 예약이 확정되면 true이고 티켓 코드를 발급한다. 다시 읽지 않고 세지 않는다")
    void confirmsCurrentPayment() {
        Reservation reservation = givenFound(ReservationStatus.PAYMENT_IN_PROGRESS, true);
        givenConfirmed(true);

        assertThat(applier.apply(RESERVATION_ID, ORDER_ID, ConfirmOutcome.succeeded()))
                .isTrue();

        verify(reservation).issueTicketCodes();
        verify(reservationRepository, never()).reload(anyLong());
        verify(reservationRepository, never()).markRefundRequired(anyLong(), anyString(), any());
        verifyNoInteractions(metrics);
    }

    @Test
    @DisplayName("확정이 0행이고 다시 읽은 예약이 같은 시도의 CONFIRMED면 같은 성공이 다시 온 것이라 세지 않는다")
    void duplicateSuccessAfterConfirmFailure() {
        Reservation reservation = givenFound(ReservationStatus.PAYMENT_IN_PROGRESS, true);
        givenConfirmed(false);
        givenReloaded(ReservationStatus.CONFIRMED, true);

        assertThat(applier.apply(RESERVATION_ID, ORDER_ID, ConfirmOutcome.succeeded()))
                .isFalse();

        verify(reservation, never()).issueTicketCodes();
        verify(reservationRepository).reload(RESERVATION_ID);
        verify(reservationRepository, never()).markRefundRequired(anyLong(), anyString(), any());
        verifyNoInteractions(metrics);
    }

    @Test
    @DisplayName("확정하지 못해 결제 중으로 남은 지금 시도는 전이, 다시 읽기, 환불 대상 표시 순서로 처리하고 표시가 1행일 때 한 번 센다")
    void unconfirmableCurrentPaymentIsMarkedThenCounted() {
        givenFound(ReservationStatus.PAYMENT_IN_PROGRESS, true);
        givenConfirmed(false);
        givenReloaded(ReservationStatus.PAYMENT_IN_PROGRESS, true);
        when(reservationRepository.markRefundRequired(RESERVATION_ID, ORDER_ID, STAMP.at()))
                .thenReturn(1);

        assertThat(applier.apply(RESERVATION_ID, ORDER_ID, ConfirmOutcome.succeeded()))
                .isFalse();

        InOrder order = inOrder(transitionExecutor, reservationRepository, metrics);
        order.verify(transitionExecutor)
                .execute(eq(ReservationTransition.CONFIRM), eq(RESERVATION_ID), eq(SEAT_IDS), any());
        order.verify(reservationRepository).reload(RESERVATION_ID);
        order.verify(reservationRepository).markRefundRequired(RESERVATION_ID, ORDER_ID, STAMP.at());
        order.verify(metrics).lateSuccess();
    }

    @Test
    @DisplayName("이미 환불 대상으로 표시된 지금 시도면 표시가 0행이라 다시 세지 않는다")
    void alreadyMarkedIsNotCountedAgain() {
        givenFound(ReservationStatus.PAYMENT_IN_PROGRESS, true);
        givenConfirmed(false);
        givenReloaded(ReservationStatus.PAYMENT_IN_PROGRESS, true);
        when(reservationRepository.markRefundRequired(RESERVATION_ID, ORDER_ID, STAMP.at()))
                .thenReturn(0);

        assertThat(applier.apply(RESERVATION_ID, ORDER_ID, ConfirmOutcome.succeeded()))
                .isFalse();

        verifyNoInteractions(metrics);
    }

    @Test
    @DisplayName("끝난 예약에 온 성공은 전이도 다시 읽기도 환불 대상 표시도 없이 한 번 센다")
    void successOnClosedReservationIsCounted() {
        givenFound(ReservationStatus.EXPIRED, true);

        assertThat(applier.apply(RESERVATION_ID, ORDER_ID, ConfirmOutcome.succeeded()))
                .isFalse();

        verifyNoInteractions(transitionExecutor);
        verify(reservationRepository, never()).reload(anyLong());
        verify(reservationRepository, never()).markRefundRequired(anyLong(), anyString(), any());
        verify(metrics).lateSuccess();
    }

    @Test
    @DisplayName("결제 중인 예약에 온 지난 시도의 성공은 지금 시도를 표시하지 않고 한 번 센다")
    void staleAttemptSuccessIsCountedWithoutMarking() {
        givenFound(ReservationStatus.PAYMENT_IN_PROGRESS, false);

        assertThat(applier.apply(RESERVATION_ID, ORDER_ID, ConfirmOutcome.succeeded()))
                .isFalse();

        verifyNoInteractions(transitionExecutor);
        verify(reservationRepository, never()).markRefundRequired(anyLong(), anyString(), any());
        verify(metrics).lateSuccess();
    }

    @Test
    @DisplayName("이미 같은 시도로 확정된 예약에 온 성공은 전이 없이 무시한다")
    void successOnConfirmedSameAttemptIsIgnored() {
        givenFound(ReservationStatus.CONFIRMED, true);

        assertThat(applier.apply(RESERVATION_ID, ORDER_ID, ConfirmOutcome.succeeded()))
                .isFalse();

        verifyNoInteractions(transitionExecutor, metrics);
        verify(reservationRepository, never()).reload(anyLong());
    }

    @Test
    @DisplayName("확정이 0행인데 다시 읽을 예약이 없으면 NoSuchElementException이다")
    void reloadMissingThrows() {
        givenFound(ReservationStatus.PAYMENT_IN_PROGRESS, true);
        givenConfirmed(false);
        when(reservationRepository.reload(RESERVATION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> applier.apply(RESERVATION_ID, ORDER_ID, ConfirmOutcome.succeeded()))
                .isInstanceOf(NoSuchElementException.class);
        verifyNoInteractions(metrics);
    }

    private Reservation givenFound(ReservationStatus status, boolean sameAttempt) {
        Reservation reservation = reservation(status, sameAttempt);
        when(reservationRepository.findById(RESERVATION_ID)).thenReturn(Optional.of(reservation));
        return reservation;
    }

    private void givenReloaded(ReservationStatus status, boolean sameAttempt) {
        Reservation reloaded = reservation(status, sameAttempt);
        when(reservationRepository.reload(RESERVATION_ID)).thenReturn(Optional.of(reloaded));
    }

    private void givenConfirmed(boolean confirmed) {
        when(transitionExecutor.execute(
                        eq(ReservationTransition.CONFIRM), eq(RESERVATION_ID), anyList(), any(IntSupplier.class)))
                .thenReturn(confirmed);
    }

    private static Reservation reservation(ReservationStatus status, boolean sameAttempt) {
        Reservation reservation = mock(Reservation.class);
        when(reservation.getId()).thenReturn(RESERVATION_ID);
        when(reservation.getStatus()).thenReturn(status);
        when(reservation.isAttempt(ORDER_ID)).thenReturn(sameAttempt);
        when(reservation.getSeatIds()).thenReturn(SEAT_IDS);
        return reservation;
    }
}
