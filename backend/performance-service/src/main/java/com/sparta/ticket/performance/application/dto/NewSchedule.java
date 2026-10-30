package com.sparta.ticket.performance.application.dto;

import java.time.LocalDateTime;

/** 회차 등록 입력. 시각은 KST로 바꾼 값이다. */
public record NewSchedule(LocalDateTime startAt, LocalDateTime bookingOpenAt, LocalDateTime bookingCloseAt) {}
