package com.sparta.ticket.reservation.booking.application.hold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.reservation.booking.application.BookingMetrics;
import com.sparta.ticket.reservation.booking.application.ChangeStamps;
import com.sparta.ticket.reservation.booking.application.facade.ReleaseResult;
import com.sparta.ticket.reservation.booking.application.facade.SeatLine;
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
 * 해제 결과와 메트릭처럼 통합 테스트로 보기 어려운 것만 확인한다. 저장소를 어떤 순서로 부르는지는 보지 않는다.
 * 상태가 실제로 바뀌는지는 BookingFacadeSeatQueryIntegrationTest와 ReservationTransitionIntegrationTest가 본다.
 */
class BookingFacadeServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long RESERVATION_ID = 10L;
    private static final ChangeStamp STAMP = new ChangeStamp(LocalDateTime.of(2026, 10, 29, 12, 0), "1");

    private final ReservationRepository reservationRepository = mock(ReservationRepository.class);
    private final ChangeStamps changeStamps = mock(ChangeStamps.class);
    private final BookingMetrics metrics = mock(BookingMetrics.class);
    private final BookingFacadeService bookingFacade =
            new BookingFacadeService(reservationRepository, changeStamps, metrics);

    @BeforeEach
    void setUp() {
        when(changeStamps.stamp()).thenReturn(STAMP);
    }

    @Test
    @DisplayName("findPaymentInProgress에 빈 목록을 넘기면 쿼리 없이 빈 집합이다")
    void findPaymentInProgressSkipsQueryForEmptyIds() {
        assertThat(bookingFacade.findPaymentInProgress(List.of())).isEmpty();

        verifyNoInteractions(reservationRepository);
    }

    @Test
    @DisplayName("hold에 회차가 없으면 INVALID_REQUEST이고 저장하지 않는다")
    void holdRejectsMissingTarget() {
        assertThatThrownBy(() -> bookingFacade.hold(
                        USER_ID,
                        null,
                        List.of(new SeatLine(100L, 10_000)),
                        STAMP.at().plusMinutes(10)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_REQUEST));

        verifyNoInteractions(reservationRepository);
    }

    @Test
    @DisplayName("release: 예약이 없으면 RESERVATION_NOT_FOUND이고 세지 않는다")
    void releaseMissingReservation() {
        when(reservationRepository.findById(RESERVATION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookingFacade.release(USER_ID, RESERVATION_ID))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ReservationErrorCode.RESERVATION_NOT_FOUND));

        verifyNoInteractions(metrics);
    }

    @Test
    @DisplayName("release: 해제가 1행이면 RELEASED이고 해제만 센다")
    void releaseCountsReleased() {
        givenOwnedReservation();
        when(reservationRepository.releaseHold(RESERVATION_ID, USER_ID, STAMP)).thenReturn(1);

        assertThat(bookingFacade.release(USER_ID, RESERVATION_ID)).isEqualTo(ReleaseResult.RELEASED);

        verify(metrics).releasedHold();
        verify(metrics, never()).expiredHold();
    }

    @Test
    @DisplayName("release: 해제가 0행이고 만료가 1행이면 EXPIRED이고 만료만 센다")
    void releaseCountsExpired() {
        givenOwnedReservation();
        when(reservationRepository.releaseHold(RESERVATION_ID, USER_ID, STAMP)).thenReturn(0);
        when(reservationRepository.expireHold(RESERVATION_ID, STAMP)).thenReturn(1);

        assertThat(bookingFacade.release(USER_ID, RESERVATION_ID)).isEqualTo(ReleaseResult.EXPIRED);

        verify(metrics).expiredHold();
        verify(metrics, never()).releasedHold();
    }

    @ParameterizedTest
    @EnumSource(
            value = ReservationStatus.class,
            names = {"RELEASED", "EXPIRED"})
    @DisplayName("release: 두 UPDATE가 0행이고 다시 읽은 상태가 끝난 선점이면 ALREADY_CLOSED이고 세지 않는다")
    void releaseAlreadyClosed(ReservationStatus status) {
        givenOwnedReservation();
        givenNoRowChangedThenReloaded(status);

        assertThat(bookingFacade.release(USER_ID, RESERVATION_ID)).isEqualTo(ReleaseResult.ALREADY_CLOSED);

        verifyNoInteractions(metrics);
    }

    @ParameterizedTest
    @EnumSource(
            value = ReservationStatus.class,
            names = {"RELEASED", "EXPIRED"},
            mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("release: 두 UPDATE가 0행이고 다시 읽은 상태가 그 밖이면(결제 중·확정·취소 등) NOT_RELEASABLE이고 세지 않는다")
    void releaseNotReleasable(ReservationStatus status) {
        givenOwnedReservation();
        givenNoRowChangedThenReloaded(status);

        assertThat(bookingFacade.release(USER_ID, RESERVATION_ID)).isEqualTo(ReleaseResult.NOT_RELEASABLE);

        verifyNoInteractions(metrics);
    }

    @Test
    @DisplayName("expireIfDue: 만료가 1행이면 true이고 만료를 한 번 센다")
    void expireIfDueCountsExpired() {
        when(reservationRepository.expireHold(RESERVATION_ID, STAMP)).thenReturn(1);

        assertThat(bookingFacade.expireIfDue(RESERVATION_ID)).isTrue();

        verify(metrics).expiredHold();
        verify(metrics, never()).releasedHold();
    }

    @Test
    @DisplayName("expireIfDue: 만료가 0행이면 false이고 세지 않는다")
    void expireIfDueSkipsMetricWhenNothingChanged() {
        when(reservationRepository.expireHold(RESERVATION_ID, STAMP)).thenReturn(0);

        assertThat(bookingFacade.expireIfDue(RESERVATION_ID)).isFalse();

        verifyNoInteractions(metrics);
    }

    private void givenOwnedReservation() {
        when(reservationRepository.findById(RESERVATION_ID))
                .thenReturn(Optional.of(reservation(ReservationStatus.HOLD)));
    }

    private void givenNoRowChangedThenReloaded(ReservationStatus status) {
        when(reservationRepository.releaseHold(RESERVATION_ID, USER_ID, STAMP)).thenReturn(0);
        when(reservationRepository.expireHold(RESERVATION_ID, STAMP)).thenReturn(0);
        when(reservationRepository.reload(RESERVATION_ID)).thenReturn(Optional.of(reservation(status)));
    }

    /** 소유자와 끝난 선점인지는 Reservation이 판단하므로 mock 대신 상태만 넣은 실제 예약을 쓴다. */
    private static Reservation reservation(ReservationStatus status) {
        Reservation reservation = Reservation.hold(
                USER_ID,
                new SaleTarget(1L, 2L, 3L),
                List.of(new SeatPrice(100L, 10_000)),
                STAMP.at().plusMinutes(10));
        ReflectionTestUtils.setField(reservation, "status", status);
        return reservation;
    }
}
