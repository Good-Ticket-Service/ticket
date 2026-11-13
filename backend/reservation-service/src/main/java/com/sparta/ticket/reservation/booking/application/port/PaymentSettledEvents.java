package com.sparta.ticket.reservation.booking.application.port;

import com.sparta.ticket.reservation.booking.domain.ReservationStatus;

/**
 * 결제 중이던 예약의 결과가 정해졌다고 알린다. 결과를 기다리는 상태 조회가 다음 조회를 기다리지 않고 바로 응답하게 한다.
 * 전이와 같은 트랜잭션 안에서 부르고, 알림은 커밋된 뒤에만 나간다. 알림이 빠져도 기다리던 조회는 대기 시간이 끝날 때 다시 읽는다.
 */
public interface PaymentSettledEvents {

    void settled(Long reservationId, ReservationStatus status);
}
