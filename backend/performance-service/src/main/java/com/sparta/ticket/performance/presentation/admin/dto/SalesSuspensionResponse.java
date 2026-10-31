package com.sparta.ticket.performance.presentation.admin.dto;

import com.sparta.ticket.performance.application.dto.SalesSuspension;

/** P15. 재개하면 suspensionReason은 null이다. version은 회차 명령의 If-Match에 쓴다. */
public record SalesSuspensionResponse(Long scheduleId, boolean salesSuspended, String suspensionReason, long version) {

    public static SalesSuspensionResponse from(SalesSuspension result) {
        return new SalesSuspensionResponse(
                result.scheduleId(), result.salesSuspended(), result.suspensionReason(), result.version());
    }
}
