package com.sparta.ticket.reservation.application;

import com.sparta.ticket.reservation.application.port.SeatPort;
import com.sparta.ticket.reservation.domain.ReservationTransition;
import com.sparta.ticket.reservation.domain.ReservationTransition.SeatEffect;
import java.util.List;
import java.util.function.IntSupplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 좌석과 예약을 함께 바꾸는 전이를 한 순서로 실행한다. 좌석을 바꾸는 전이는 좌석을 먼저 잠그고(seat의 선점과 같은 순서),
 * 조건부 UPDATE가 1행일 때만 좌석에 반영한다. 부르는 쪽의 트랜잭션 안에서만 쓴다.
 */
@Component
@RequiredArgsConstructor
public class ReservationTransitionExecutor {

    private final SeatPort seatPort;

    /** 전이가 반영됐으면 true. 조건부 UPDATE가 0행이면 좌석을 건드리지 않고 false. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean execute(
            ReservationTransition transition, Long reservationId, List<Long> seatIds, IntSupplier conditionalUpdate) {
        if (transition.seatEffect() != SeatEffect.NONE) {
            seatPort.lockSeats(seatIds);
        }
        if (conditionalUpdate.getAsInt() == 0) {
            return false;
        }
        Runnable seatChange = switch (transition.seatEffect()) {
            case NONE -> () -> {};
            case SELL -> () -> seatPort.sell(reservationId, seatIds);
            case RELEASE -> () -> seatPort.release(reservationId, seatIds);
        };
        seatChange.run();
        return true;
    }
}
