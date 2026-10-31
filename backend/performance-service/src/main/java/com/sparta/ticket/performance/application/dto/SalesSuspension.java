package com.sparta.ticket.performance.application.dto;

import com.sparta.ticket.performance.domain.schedule.Schedule;

/** 판매 중지·재개 결과(P15). version은 다음 회차 명령의 If-Match에 쓴다. */
public record SalesSuspension(Long scheduleId, boolean salesSuspended, String suspensionReason, long version) {

    public static SalesSuspension of(Schedule schedule) {
        return new SalesSuspension(
                schedule.getId(), schedule.isSalesSuspended(), schedule.getSuspensionReason(), schedule.getVersion());
    }
}
