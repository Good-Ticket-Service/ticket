package com.sparta.ticket.reservation.infrastructure.persistence;

import com.sparta.ticket.reservation.domain.Reservation;
import java.util.Optional;

/** Spring Data 리포지토리에 붙는 조각. 구현은 {@link ReservationReloaderImpl}이다. */
public interface ReservationReloader {

    Optional<Reservation> reload(Long id);
}
