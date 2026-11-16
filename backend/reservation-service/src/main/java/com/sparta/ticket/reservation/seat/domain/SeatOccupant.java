package com.sparta.ticket.reservation.seat.domain;

/**
 * 좌석의 회차·상태·점유 예약만 읽은 값(#449 후속 1-C). Entity가 아니라 영속성 컨텍스트에 남지 않는다. 같은 트랜잭션에서 뒤이어 좌석을
 * 잠그고 읽어도 잠근 뒤의 최신 값을 받는다.
 */
public record SeatOccupant(Long scheduleId, SeatStatus status, Long reservationId) {}
