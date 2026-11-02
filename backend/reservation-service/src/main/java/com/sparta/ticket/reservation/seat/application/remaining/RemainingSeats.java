package com.sparta.ticket.reservation.seat.application.remaining;

import com.sparta.ticket.reservation.seat.domain.GradeRemaining;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 회차의 등급별 잔여석 집계 결과. 캐시에 이 값을 그대로 둔다.
 *
 * @param grades 판매 대상 좌석이 있는 등급만, 등급 id 오름차순. 좌석이 없는 회차면 빈 목록
 * @param asOf 집계한 시각(공용 Clock, KST). 캐시에서 줄 때도 이 시각을 그대로 준다
 */
public record RemainingSeats(Long scheduleId, List<GradeRemaining> grades, LocalDateTime asOf) {}
