package com.sparta.ticket.reservation.application;

import com.sparta.ticket.reservation.domain.SeatPrice;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * seat 모듈이 부르는 booking의 진입점. 예약은 booking만 만들고 바꾼다(설계서 3.1, 합의 전 S2).
 * 좌석 선점과 같은 트랜잭션 안에서 불러야 한다.
 */
public interface BookingFacade {

    /** 좌석 선점 트랜잭션 안에서 HOLD 예약과 좌석마다 티켓을 만들고 예약 id를 돌려준다. 가격은 선점 시점의 좌석 가격이다. */
    Long hold(UUID userId, Long scheduleId, List<SeatPrice> seats, LocalDateTime holdExpiresAt);
}
