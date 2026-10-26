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

    /**
     * 사용자가 선점을 직접 해제할 때 seat가 해제 트랜잭션 안에서 좌석을 먼저 잠근 뒤 부른다. 좌석 반환은 seat가 한다.
     * HOLD이면 RELEASED로 바꾼다. 선점 시간이 이미 지났으면 EXPIRED로 정리한다. 이미 RELEASED나 EXPIRED면 아무것도 하지 않는다.
     * 어느 경우든 정상으로 끝나면 seat는 이 예약이 잡고 있는 좌석을 돌려주면 된다.
     * 결제 중이거나 끝난 예약은 INVALID_STATUS_TRANSITION, 본인 예약이 아니면 FORBIDDEN이다.
     */
    void release(UUID userId, Long reservationId);
}
