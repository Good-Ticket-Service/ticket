package com.sparta.ticket.performance.application.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 공연 기간. 활성 회차(삭제·취소되지 않은 회차) 시작 시각의 첫 날짜와 마지막 날짜다. */
public record PerformancePeriod(LocalDate from, LocalDate to) {

    /** 활성 회차가 없어 시작 시각이 null이면 기간도 null이다. */
    public static PerformancePeriod of(LocalDateTime firstStartAt, LocalDateTime lastStartAt) {
        if (firstStartAt == null || lastStartAt == null) {
            return null;
        }
        return new PerformancePeriod(firstStartAt.toLocalDate(), lastStartAt.toLocalDate());
    }
}
