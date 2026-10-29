package com.sparta.ticket.reservation.booking.application.facade;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * seat 모듈이 부르는 booking의 진입점. 예약은 booking만 만들고 바꾼다(#34).
 * 모든 메서드는 seat의 트랜잭션 안에서 불러야 한다(MANDATORY). 이 패키지의 타입만 주고받으므로,
 * 나중에 Facade를 Feign으로 바꾸면 이 타입이 그대로 요청·응답 DTO가 된다. 그때는 좌석과 예약이 따로 커밋되므로 보상이 필요하다.
 */
public interface BookingFacade {

    /**
     * HOLD 예약과 좌석마다 티켓을 만들고 예약 id를 돌려준다. 가격은 선점 시점의 좌석 가격이다.
     * target의 공연·판매자·회차 시작 시각·공연명도 선점 시점 값으로 저장한다. 하나라도 없으면 INVALID_REQUEST다.
     * 같은 회원이 같은 회차에 진행 중인 예약을 또 만들면 부분 UNIQUE(uk_p_reservations_active_user_schedule) 위반을
     * 잡지 않고 그대로 던진다. 트랜잭션이 롤백 전용이 되므로 seat는 트랜잭션 밖에서 이 제약 이름을 보고 409로 바꾼다.
     */
    Long hold(Long userId, HoldTarget target, List<SeatLine> seats, LocalDateTime holdExpiresAt);

    /**
     * 예약 하나를 읽는다. 상태와 상관없이 돌려준다. seat는 선점 해제 때 이 결과의 좌석 id로 좌석을 먼저 잠근 뒤
     * {@link #release}를 부른다(잠금 순서: 좌석 → 예약).
     */
    Optional<ReservationView> getReservation(Long reservationId);

    /**
     * 사용자가 선점을 직접 해제한다. seat가 좌석을 먼저 잠근 뒤 부르고, 좌석 반환은 seat가 한다.
     * 결과는 {@link ReleaseResult}로 돌려준다. 예약이 없으면 RESERVATION_NOT_FOUND, 본인 예약이 아니면 FORBIDDEN 예외다.
     */
    ReleaseResult release(Long userId, Long reservationId);

    /**
     * 이 회원이 이 회차에 진행 중인(HOLD·결제 중·확정·취소 중) 예약의 좌석 수. 1인 매수 제한에 쓴다.
     * 취소 중에도 좌석은 판매된 그대로라 센다. 만료·해제·취소된 예약은 세지 않는다.
     */
    int countActiveSeats(Long userId, Long scheduleId);

    /**
     * 이 회원의 이 회차 HOLD 예약을 만료 여부와 상관없이 읽는다. 없으면 빈 값이다.
     * seat는 같은 좌석 재요청과 본인의 만료된 선점 정리를 이 결과로 판단한다.
     */
    Optional<ReservationView> findActiveHold(Long userId, Long scheduleId);

    /**
     * HOLD이고 선점 시간이 지났을 때만 EXPIRED로 바꾸고 true를 돌려준다. 그 밖에는 아무것도 바꾸지 않고 false다.
     * 결제 시작과 둘 다 HOLD 조건부 UPDATE라 한쪽만 성공한다. 좌석 반환은 seat가 한다.
     */
    boolean expireIfDue(Long reservationId);

    /** 넘긴 예약 중 결제 중인 것의 id. seat가 예약 테이블을 JOIN하지 않고 만료된 선점 좌석을 판정할 때 쓴다. */
    Set<Long> findPaymentInProgress(Collection<Long> reservationIds);
}
