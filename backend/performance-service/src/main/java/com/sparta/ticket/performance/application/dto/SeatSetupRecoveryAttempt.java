package com.sparta.ticket.performance.application.dto;

/** 결과 반영 시 임대 이후 시작된 다른 복구 시도와 구분한다. */
public record SeatSetupRecoveryAttempt(SeatSetupCommand command, int attempt) {}
