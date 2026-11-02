package com.sparta.ticket.reservation.seat.presentation.dto;

import com.sparta.ticket.reservation.seat.application.remaining.RemainingSeatsResult;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 등급별 잔여석 응답. asOf는 집계 시각이고, 캐시에서 준 응답도 원래 집계 시각이다.
 *
 * <p>Note: Clock과 DB의 시각은 시간대 없는 KST 값이라, 응답에서 서울 오프셋을 붙인다.
 */
public record RemainingSeatsResponse(Long scheduleId, List<Grade> grades, OffsetDateTime asOf, boolean cached) {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    public static RemainingSeatsResponse from(RemainingSeatsResult result) {
        return new RemainingSeatsResponse(
                result.scheduleId(),
                result.grades().stream()
                        .map(grade -> new Grade(
                                grade.venueSeatGradeId(), grade.gradeName(), Math.toIntExact(grade.remaining())))
                        .toList(),
                result.asOf().truncatedTo(ChronoUnit.SECONDS).atZone(SEOUL).toOffsetDateTime(),
                result.cached());
    }

    /** @param remaining 0이면 판매 대상 좌석이 모두 선점·판매됐다(화면은 "매진") */
    public record Grade(Long venueSeatGradeId, String gradeName, int remaining) {}
}
