package com.sparta.ticket.reservation.seat.application.setup;

import com.sparta.ticket.reservation.seat.domain.ScheduleSeatSpec;
import java.util.List;

/**
 * 좌석 준비 요청. seats는 회차에 둘 좌석 전체다.
 *
 * @param requestId 공연 서비스가 만든 준비 요청 ID. 저장하지 않고 응답에 그대로 돌려준다
 * @param configVersion 회차 좌석 구성 버전. 1 이상
 */
public record SeatSetupCommand(String requestId, Long scheduleId, long configVersion, List<ScheduleSeatSpec> seats) {}
