package com.sparta.ticket.payment.presentation.dto.internal;

import com.sparta.ticket.payment.application.dto.payment.ConfirmResult;

public record ConfirmResponse(Long paymentId, String status, String failureType, String failureCode) {
    public static ConfirmResponse from(ConfirmResult result) {
        return new ConfirmResponse(
                result.paymentId(),
                result.status().name(),
                result.failureType() == null ? null : result.failureType().name(),
                result.failureCode());
    }
}
