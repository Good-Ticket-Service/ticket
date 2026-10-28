package com.sparta.ticket.reservation.booking.application.port;

import java.util.List;

/**
 * booking이 seat 모듈에 바라는 좌석 연산(SeatFacade, #34). 모두 호출한 쪽의 트랜잭션 안에서 실행된다.
 * 좌석과 예약을 함께 바꾸는 트랜잭션은 lockSeats를 먼저 부른다. 잠금 순서가 좌석 → 예약이어야 seat의 선점과 교착하지 않는다.
 */
public interface SeatPort {

    /** 좌석 행을 id 오름차순으로 잠근다. 좌석이 모두 있고 모두 이 예약의 HOLD이면 true다. */
    boolean lockSeats(List<Long> seatIds, Long reservationId);

    /**
     * 확정: 이 예약의 HOLD 좌석을 SOLD로 바꾼다. 한 좌석이라도 바꾸지 못하면 예외를 던진다.
     * 그대로 커밋하면 예약은 확정됐는데 좌석은 팔리지 않은 상태가 남으므로 트랜잭션 전체를 되돌린다.
     */
    void sell(List<Long> seatIds, Long reservationId);

    /** 만료·취소: 이 예약이 가진 좌석을 다시 팔 수 있게 돌려준다. 잠금 시점에 이 예약이 가진 좌석을 다 돌려주지 못하면 예외를 던진다. */
    void release(List<Long> seatIds, Long reservationId);
}
