package com.sparta.ticket.reservation.booking.application;

import com.sparta.ticket.reservation.booking.application.port.SeatPort;
import com.sparta.ticket.reservation.booking.domain.ReservationTransition;
import com.sparta.ticket.reservation.booking.domain.ReservationTransition.SeatEffect;
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

    /**
     * 전이가 반영됐으면 true. 조건부 UPDATE가 0행이면 좌석을 건드리지 않고 false.
     * 판매 전이는 잠근 좌석이 모두 이 예약의 HOLD일 때만 예약을 바꾼다. 아니면 예약도 좌석도 바꾸지 않고 false다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean execute(
            ReservationTransition transition, Long reservationId, List<Long> seatIds, IntSupplier conditionalUpdate) {
        if (transition.seatEffect() != SeatEffect.NONE) {
            boolean heldByThis = seatPort.lockSeats(seatIds, reservationId);
            if (transition.seatEffect() == SeatEffect.SELL && !heldByThis) {
                // Note: 좌석이 이 예약의 HOLD가 아니면 확정할 수 없으므로 예약도 좌석도 바꾸지 않는다. 같은 성공이 두 번 와서
                // 앞선 반영이 이미 SOLD로 바꾼 경우도 여기로 오므로, 문제인지는 예약을 다시 읽은 호출한 쪽이 가린다.
                return false;
            }
        }
        if (conditionalUpdate.getAsInt() == 0) {
            return false;
        }
        Runnable seatChange = switch (transition.seatEffect()) {
            case NONE -> () -> {};
            case SELL -> () -> seatPort.sell(seatIds, reservationId);
            case RELEASE -> () -> seatPort.release(seatIds, reservationId);
        };
        seatChange.run();
        return true;
    }
}
