package com.sparta.ticket.reservation.application;

import com.sparta.ticket.reservation.domain.Reservation;
import com.sparta.ticket.reservation.domain.ReservationRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BookingFacadeService implements BookingFacade {

    private final ReservationRepository reservationRepository;

    /** 좌석과 예약이 한 트랜잭션에서 바뀌어야 하므로, 바깥 트랜잭션 없이 부르면 예외로 막는다. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID hold(UUID userId, UUID scheduleId, List<UUID> seatIds, LocalDateTime holdExpiresAt) {
        return reservationRepository
                .save(Reservation.hold(userId, scheduleId, seatIds, holdExpiresAt))
                .getId();
    }
}
