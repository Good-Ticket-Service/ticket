package com.sparta.ticket.reservation.booking.infrastructure.seat;

import com.sparta.ticket.reservation.booking.application.port.SeatPort;
import com.sparta.ticket.reservation.seat.application.facade.SeatFacade;
import com.sparta.ticket.reservation.seat.application.facade.SeatLock;
import com.sparta.ticket.reservation.seat.application.facade.SeatState;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * SeatPort를 seat 모듈의 SeatFacade(#34)로 잇는다. seat의 좌석 상태와 바뀐 행 수를 booking의 말로 바꾼다.
 * SeatFacade는 상태를 바꾸지 못해도 예외 없이 행 수로 알리므로, 행 수가 모자라면 여기서 예외를 던져 트랜잭션을 되돌린다.
 */
@Component
@RequiredArgsConstructor
public class SeatFacadeAdapter implements SeatPort {

    private final SeatFacade seatFacade;

    @Override
    public boolean lockSeats(List<Long> seatIds, Long reservationId) {
        List<SeatLock> locks = seatFacade.lockSeats(seatIds);
        return locks.size() == distinctCount(seatIds)
                && locks.stream()
                        .allMatch(lock -> lock.state() == SeatState.HOLD && reservationId.equals(lock.reservationId()));
    }

    @Override
    public void sell(List<Long> seatIds, Long reservationId) {
        expectAllChanged("sell", seatFacade.sell(seatIds, reservationId), seatIds, reservationId);
    }

    /**
     * Note: 반환은 요청한 좌석 수가 아니라 잠금 시점에 이 예약이 가진 좌석 수와 맞춘다. 논리 삭제돼 잠금에서 빠진 좌석까지 세면
     * 만료가 매번 롤백되고, 만료 후보는 오래된 순으로 고르므로 같은 예약이 맨 앞에서 뒤의 만료를 모두 막는다.
     * 이미 잠근 좌석을 다시 잠그는 것이라 기다리지 않는다.
     */
    @Override
    public void release(List<Long> seatIds, Long reservationId) {
        long owned = seatFacade.lockSeats(seatIds).stream()
                .filter(lock -> reservationId.equals(lock.reservationId()))
                .count();
        expectChanged("release", seatFacade.release(seatIds, reservationId), owned, seatIds, reservationId);
    }

    private static void expectAllChanged(String operation, int changed, List<Long> seatIds, Long reservationId) {
        expectChanged(operation, changed, distinctCount(seatIds), seatIds, reservationId);
    }

    private static void expectChanged(
            String operation, int changed, long expected, List<Long> seatIds, Long reservationId) {
        if (changed != expected) {
            throw new IllegalStateException("좌석 %s가 %d/%d건만 반영됐다. reservationId=%d, seatIds=%s"
                    .formatted(operation, changed, expected, reservationId, seatIds));
        }
    }

    private static long distinctCount(List<Long> seatIds) {
        return seatIds.stream().distinct().count();
    }
}
