package com.sparta.ticket.reservation.seat.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.booking.application.facade.BookingFacade;
import com.sparta.ticket.reservation.domain.ReservationErrorCode;
import com.sparta.ticket.reservation.seat.application.dto.SelectableSeatResult;
import com.sparta.ticket.reservation.seat.application.dto.SelectableSeatsResult;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeat;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeatRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 결제 중 여부를 묻는 횟수와 최종 판정처럼, 통합 테스트로 세기 어려운 것을 본다. */
class SelectableSeatQueryServiceTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 20, 0);
    private static final Long SCHEDULE = 1L;

    private final ScheduleSeatRepository scheduleSeatRepository = mock(ScheduleSeatRepository.class);
    private final BookingFacade bookingFacade = mock(BookingFacade.class);
    private final SelectableSeatQueryService service = new SelectableSeatQueryService(
            scheduleSeatRepository, bookingFacade, Clock.fixed(NOW.atZone(SEOUL).toInstant(), SEOUL));

    private long nextId = 1;

    private ScheduleSeat available() {
        ScheduleSeat seat = ScheduleSeat.create(SCHEDULE, nextId, 7L, "VIP", 150_000, "A", "1", 1, true, 3L);
        ReflectionTestUtils.setField(seat, "id", nextId++);
        return seat;
    }

    private ScheduleSeat heldUntil(Long reservationId, LocalDateTime holdExpiresAt) {
        ScheduleSeat seat = available();
        seat.hold(reservationId, holdExpiresAt, holdExpiresAt.minusMinutes(10));
        return seat;
    }

    private static List<Boolean> selectable(SelectableSeatsResult result) {
        return result.seats().stream().map(SelectableSeatResult::selectable).toList();
    }

    @Test
    @DisplayName("만료 HOLD의 예약 id를 모아 결제 중 여부를 한 번만 묻고, 결제 중인 예약의 좌석만 선택할 수 없다")
    void asksPaymentInProgressOnce() {
        ScheduleSeat paying = heldUntil(10L, NOW);
        ScheduleSeat abandoned = heldUntil(20L, NOW.minusMinutes(1));
        ScheduleSeat sameReservation = heldUntil(20L, NOW.minusMinutes(1));
        when(scheduleSeatRepository.findAllBySchedule(SCHEDULE))
                .thenReturn(List.of(paying, abandoned, sameReservation));
        when(bookingFacade.findPaymentInProgress(List.of(10L, 20L))).thenReturn(Set.of(10L));

        SelectableSeatsResult result = service.getSelectableSeats(SCHEDULE);

        verify(bookingFacade, times(1)).findPaymentInProgress(List.of(10L, 20L));
        assertThat(selectable(result)).containsExactly(false, true, true);
    }

    @Test
    @DisplayName("만료 HOLD가 없으면 결제 중 여부를 묻지 않는다")
    void skipsPaymentCheckWithoutExpiredHold() {
        when(scheduleSeatRepository.findAllBySchedule(SCHEDULE))
                .thenReturn(List.of(available(), heldUntil(10L, NOW.plusSeconds(1))));

        SelectableSeatsResult result = service.getSelectableSeats(SCHEDULE);

        verify(bookingFacade, never()).findPaymentInProgress(any());
        assertThat(selectable(result)).containsExactly(true, false);
    }

    @Test
    @DisplayName("판정 시각은 공용 Clock이고, 구성 버전은 회차 좌석의 버전이다")
    void usesClockAndConfigVersion() {
        when(scheduleSeatRepository.findAllBySchedule(SCHEDULE)).thenReturn(List.of(available()));

        SelectableSeatsResult result = service.getSelectableSeats(SCHEDULE);

        assertThat(result.asOf()).isEqualTo(NOW);
        assertThat(result.configVersion()).isEqualTo(3L);
        assertThat(result.scheduleId()).isEqualTo(SCHEDULE);
    }

    @Test
    @DisplayName("회차에 좌석이 하나도 없으면 SEAT_NOT_FOUND다")
    void noSeats() {
        when(scheduleSeatRepository.findAllBySchedule(SCHEDULE)).thenReturn(List.of());

        assertThatThrownBy(() -> service.getSelectableSeats(SCHEDULE))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ReservationErrorCode.SEAT_NOT_FOUND);
    }
}
