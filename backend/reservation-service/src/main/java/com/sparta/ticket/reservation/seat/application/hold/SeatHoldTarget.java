package com.sparta.ticket.reservation.seat.application.hold;

/**
 * 트랜잭션 전에 잠금 없이 읽은 요청 좌석의 모습. 잠근 뒤 다시 확인하므로 판단의 근거가 아니라, 어떤 좌석을 함께 잠글지 고르는 데만 쓴다.
 *
 * @param scheduleSeatId 요청 좌석
 * @param heldReservationId 읽을 때 이 좌석을 잡고 있던 예약. 없으면 null. 만료 여부와 관계없이 그 예약의 좌석 전체를 함께 잠근다.
 *     판매 검증을 기다리는 사이 만료될 수 있어, 만료 판정은 잠근 뒤에 한다
 */
record SeatHoldTarget(Long scheduleSeatId, Long heldReservationId) {}
