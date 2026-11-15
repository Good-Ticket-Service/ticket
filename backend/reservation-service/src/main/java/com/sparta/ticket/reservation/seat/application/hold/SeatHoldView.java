package com.sparta.ticket.reservation.seat.application.hold;

import com.sparta.ticket.reservation.seat.application.port.StoredHold;
import java.time.LocalDateTime;

/**
 * Redis 선점 조회 결과(#449). 만료 시각은 seat·booking이 함께 쓰는 Clock의 시간대로 바꾼 값이다. 다른 좌석 응답과 같이 표현 계층이
 * 이 값을 KST로 내보낸다.
 */
public record SeatHoldView(StoredHold hold, LocalDateTime holdExpiresAt) {}
