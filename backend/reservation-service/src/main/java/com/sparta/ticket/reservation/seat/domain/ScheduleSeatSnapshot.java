package com.sparta.ticket.reservation.seat.domain;

/**
 * 좌석 준비가 잠근 회차 좌석 한 행의 현재 상태. 논리 삭제된 행도 포함한다.
 * 교체할 때 공연장 좌석 ID로 기존 행을 찾아 갱신·복원하므로, 삭제된 행까지 알아야 UK(schedule_id, venue_seat_id) 위반 없이 다시 쓸 수 있다.
 */
public record ScheduleSeatSnapshot(Long id, Long venueSeatId, SeatStatus status, long configVersion, boolean deleted) {}
