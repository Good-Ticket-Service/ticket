package com.sparta.ticket.reservation.booking.application.schedulecancel;

import java.time.LocalDateTime;

/** 공연에서 회차가 취소됐다는 사실. 메시지 형식과 무관한 booking의 말이다. */
public record ScheduleCanceled(String eventId, Long scheduleId, LocalDateTime canceledAt) {}
