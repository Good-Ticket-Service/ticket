package com.sparta.ticket.reservation.application.port;

import java.util.List;
import java.util.UUID;

/**
 * booking이 seat 모듈에 바라는 좌석 연산(설계서 3.1 SeatFacade, 합의 전 S3). 모두 호출한 쪽의 트랜잭션 안에서 실행된다.
 * seat 담당의 구현이 오면 어댑터가 그것을 부른다.
 */
public interface SeatPort {

    /** 좌석 행을 id 오름차순으로 잠근다. 좌석과 예약을 함께 바꾸는 트랜잭션은 이것을 먼저 부른다. */
    void lockSeats(List<UUID> seatIds);

    /** 확정: HOLD → SOLD. */
    void sell(List<UUID> seatIds);

    /** 만료·취소: 좌석을 다시 팔 수 있게 돌려준다. */
    void release(List<UUID> seatIds);

    /** 좌석 가격 스냅샷의 합계. 결제 기본 금액이다. */
    long priceSnapshot(List<UUID> seatIds);
}
