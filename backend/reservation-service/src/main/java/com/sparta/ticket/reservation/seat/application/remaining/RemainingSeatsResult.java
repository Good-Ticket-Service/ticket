package com.sparta.ticket.reservation.seat.application.remaining;

import com.sparta.ticket.reservation.seat.domain.GradeRemaining;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 등급별 잔여석 조회 결과.
 *
 * @param cached 이번 요청이 직접 집계하지 않고 캐시에 있던 값(또는 같은 순간 다른 요청이 집계한 값)을 받았으면 true
 */
public record RemainingSeatsResult(Long scheduleId, List<GradeRemaining> grades, LocalDateTime asOf, boolean cached) {

    static RemainingSeatsResult of(RemainingSeats seats, boolean cached) {
        return new RemainingSeatsResult(seats.scheduleId(), seats.grades(), seats.asOf(), cached);
    }
}
