package com.sparta.ticket.reservation.seat.application;

import com.sparta.ticket.reservation.seat.application.facade.SeatFacade;
import com.sparta.ticket.reservation.seat.application.facade.SeatLock;
import com.sparta.ticket.reservation.seat.application.facade.SeatState;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeat;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeatRepository;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * booking의 확정·만료·취소가 좌석을 맞추는 구현.
 *
 * Note: sell·release도 UPDATE 전에 좌석을 id 오름차순으로 먼저 잠근다. IN 절 UPDATE가 행을 잠그는 순서는 id 순서로 보장되지 않아,
 * 호출자가 lockSeats를 건너뛰면 다른 순서로 잠그는 선점과 교착할 수 있다. 이미 잠근 행이면 다시 잠가도 비용이 거의 없다.
 * 같은 id가 여러 번 와도 한 행만 바뀌므로 중복을 빼고 처리한다.
 *
 * Note: 세 메서드 모두 MANDATORY다. 좌석 잠금은 트랜잭션이 끝날 때 풀리므로, 따로 트랜잭션을 열면 예약을 바꾸기 전에 잠금이 풀려
 * 좌석과 예약이 다른 시점의 상태로 어긋날 수 있다. booking의 트랜잭션 안에서 좌석 → 예약 순서로 함께 커밋되게 한다.
 */
@Service
@RequiredArgsConstructor
public class SeatFacadeService implements SeatFacade {

    private final ScheduleSeatRepository scheduleSeatRepository;
    private final SeatChangeStamps changeStamps;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<SeatLock> lockSeats(Collection<Long> scheduleSeatIds) {
        List<Long> ids = distinct(scheduleSeatIds);
        if (ids.isEmpty()) {
            return List.of();
        }
        return scheduleSeatRepository.findAllByIdsForUpdate(ids).stream()
                .map(SeatFacadeService::toLock)
                .toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int sell(Collection<Long> scheduleSeatIds, Long reservationId) {
        List<Long> ids = distinct(scheduleSeatIds);
        if (ids.isEmpty()) {
            return 0;
        }
        scheduleSeatRepository.findAllByIdsForUpdate(ids);
        return scheduleSeatRepository.sell(ids, reservationId, changeStamps.now(), changeStamps.by());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int release(Collection<Long> scheduleSeatIds, Long reservationId) {
        List<Long> ids = distinct(scheduleSeatIds);
        if (ids.isEmpty()) {
            return 0;
        }
        scheduleSeatRepository.findAllByIdsForUpdate(ids);
        return scheduleSeatRepository.release(ids, reservationId, changeStamps.now(), changeStamps.by());
    }

    private static List<Long> distinct(Collection<Long> ids) {
        return ids.stream().distinct().toList();
    }

    private static SeatLock toLock(ScheduleSeat seat) {
        return new SeatLock(
                seat.getId(),
                SeatState.valueOf(seat.getStatus().name()),
                seat.getReservationId(),
                seat.getHoldExpiresAt());
    }
}
