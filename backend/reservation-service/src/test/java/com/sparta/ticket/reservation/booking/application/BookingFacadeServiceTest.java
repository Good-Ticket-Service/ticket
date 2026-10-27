package com.sparta.ticket.reservation.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sparta.ticket.reservation.booking.domain.ReservationRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 쿼리를 보내는지처럼 통합 테스트로 보기 어려운 것만 확인한다. 나머지 동작은 BookingFacadeSeatQueryIntegrationTest가 본다. */
class BookingFacadeServiceTest {

    private final ReservationRepository reservationRepository = mock(ReservationRepository.class);
    private final BookingFacadeService bookingFacade =
            new BookingFacadeService(reservationRepository, mock(ChangeStamps.class), mock(BookingMetrics.class));

    @Test
    @DisplayName("findPaymentInProgress에 빈 목록을 넘기면 쿼리 없이 빈 집합이다")
    void findPaymentInProgressSkipsQueryForEmptyIds() {
        assertThat(bookingFacade.findPaymentInProgress(List.of())).isEmpty();

        verifyNoInteractions(reservationRepository);
    }
}
