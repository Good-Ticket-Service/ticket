package com.sparta.ticket.reservation.booking.application.port;

import java.util.List;

/**
 * booking이 seat 모듈에 바라는 좌석 연산(설계서 3.1 SeatFacade, 합의 전 S3). 모두 호출한 쪽의 트랜잭션 안에서 실행된다.
 * seat 담당의 구현이 오면 어댑터가 그것을 부른다.
 */
public interface SeatPort {

    /** 좌석 행을 id 오름차순으로 잠근다. 좌석과 예약을 함께 바꾸는 트랜잭션은 이것을 먼저 부른다. */
    void lockSeats(List<Long> seatIds);

    /** 확정: 이 예약이 잡고 있는 좌석을 HOLD → SOLD. 좌석을 이 예약이 잡고 있지 않으면 예외를 던진다. */
    void sell(Long reservationId, List<Long> seatIds);

    /**
     * 만료·취소: 좌석을 다시 팔 수 있게 돌려준다. 좌석을 지금 이 예약이 잡고 있을 때만 돌려준다.
     * 만료된 선점은 정리 전에 다른 예약이 다시 잡을 수 있으므로, 그 좌석은 건드리지 않고 넘어간다.
     */
    void release(Long reservationId, List<Long> seatIds);
}
