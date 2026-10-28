package com.sparta.ticket.reservation.seat.application.facade;

import java.util.Collection;
import java.util.List;

/**
 * booking이 부르는 seat의 진입점. booking은 좌석 테이블을 직접 읽거나 바꾸지 않고 이 인터페이스로만 좌석을 맞춘다(#34).
 * 모든 메서드는 호출자의 트랜잭션 안에서만 동작한다. 잠금 순서는 좌석(id 오름차순) → 예약이다.
 * 상태를 바꾸지 못한 좌석은 예외 없이 바뀐 행 수로 알린다. 같은 id는 한 번으로 센다.
 * sell·release가 돌려준 행 수가 요청한 좌석 수(중복 제외)와 다르면 호출자가 예외를 던져 트랜잭션 전체를 롤백해야 한다.
 * 그대로 커밋하면 예약은 확정·만료됐는데 좌석은 바뀌지 않은 상태가 남는다.
 */
public interface SeatFacade {

    /** 좌석을 id 오름차순으로 잠그고 지금 상태를 돌려준다. 없는 좌석은 결과에서 빠진다. */
    List<SeatLock> lockSeats(Collection<Long> scheduleSeatIds);

    /** 이 예약이 잡은 HOLD 좌석만 SOLD로 바꾸고 만료 시각을 비운다. 바뀐 행 수를 돌려준다. */
    int sell(Collection<Long> scheduleSeatIds, Long reservationId);

    /** 이 예약이 가진 좌석만 AVAILABLE로 돌린다. 이미 다른 예약에 넘어간 좌석은 건드리지 않는다. 바뀐 행 수를 돌려준다. */
    int release(Collection<Long> scheduleSeatIds, Long reservationId);
}
