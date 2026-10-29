package com.sparta.ticket.reservation.booking.application.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sparta.ticket.reservation.booking.application.BookingMetrics;
import com.sparta.ticket.reservation.booking.application.ChangeStamps;
import com.sparta.ticket.reservation.booking.application.ReservationTransitionExecutor;
import com.sparta.ticket.reservation.booking.application.port.ConfirmOutcome;
import com.sparta.ticket.reservation.booking.domain.ChangeStamp;
import com.sparta.ticket.reservation.booking.domain.Reservation;
import com.sparta.ticket.reservation.booking.domain.ReservationRepository;
import com.sparta.ticket.reservation.booking.domain.ReservationStatus;
import com.sparta.ticket.reservation.booking.domain.ReservationTransition;
import com.sparta.ticket.reservation.booking.domain.SaleTarget;
import com.sparta.ticket.reservation.booking.domain.SeatPrice;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 결제 성공 반영의 갈래마다 결과, 좌석 전이, 티켓 발급, 환불 대상 표시, 메트릭을 본다. 다시 읽는지나 부르는 순서는 보지 않는다.
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
    @DisplayName("지금 시도의 결제 중 예약이 확정되면 true이고 티켓 코드를 발급한다. 환불 대상으로 표시하거나 세지 않는다")
    void confirmsCurrentPayment() {
        Reservation reservation = givenFound(ReservationStatus.PAYMENT_IN_PROGRESS, true);
        givenConfirmed(true);

        assertThat(applier.apply(RESERVATION_ID, ORDER_ID, ConfirmOutcome.succeeded()))
                .isTrue();

        verify(reservation).issueTicketCodes();
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
        verify(reservationRepository, never()).markRefundRequired(anyLong(), anyString(), any());
        verifyNoInteractions(metrics);
    }

    @Test
    @DisplayName("확정하지 못해 결제 중으로 남은 지금 시도는 환불 대상으로 표시하고, 표시가 1행일 때 한 번 센다")
    void unconfirmableCurrentPaymentIsMarkedThenCounted() {
        givenFound(ReservationStatus.PAYMENT_IN_PROGRESS, true);
        givenConfirmed(false);
        givenReloaded(ReservationStatus.PAYMENT_IN_PROGRESS, true);
        when(reservationRepository.markRefundRequired(RESERVATION_ID, ORDER_ID, STAMP.at()))
                .thenReturn(1);

        assertThat(applier.apply(RESERVATION_ID, ORDER_ID, ConfirmOutcome.succeeded()))
                .isFalse();

        verify(reservationRepository).markRefundRequired(RESERVATION_ID, ORDER_ID, STAMP.at());
        verify(metrics).lateSuccess();
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
    @DisplayName("끝난 예약에 온 성공은 좌석을 건드리지 않고 환불 대상 표시 없이 한 번 센다")
    void successOnClosedReservationIsCounted() {
        givenFound(ReservationStatus.EXPIRED, true);

        assertThat(applier.apply(RESERVATION_ID, ORDER_ID, ConfirmOutcome.succeeded()))
                .isFalse();

        verifyNoInteractions(transitionExecutor);
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

    /** 상태 판단은 Reservation의 실제 코드를 타게 하고, 티켓 발급 여부만 확인하려고 spy로 감싼다. */
    private static Reservation reservation(ReservationStatus status, boolean sameAttempt) {
        Reservation reservation = Reservation.hold(
                1L,
                new SaleTarget(1L, 2L, 3L),
                SEAT_IDS.stream().map(seatId -> new SeatPrice(seatId, 10_000)).toList(),
                STAMP.at().plusMinutes(10));
        ReflectionTestUtils.setField(reservation, "id", RESERVATION_ID);
        ReflectionTestUtils.setField(reservation, "status", status);
        ReflectionTestUtils.setField(reservation, "orderId", sameAttempt ? ORDER_ID : "order-other");
        return spy(reservation);
    }
}
