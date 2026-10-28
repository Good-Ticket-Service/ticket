package com.sparta.ticket.payment.presentation.dto.internal;

import com.sparta.ticket.payment.application.dto.refund.RefundResult;

public record RefundResponse(String refundStatus, int refundAmount) {
    public static RefundResponse from(RefundResult result) {
        return new RefundResponse(result.refundStatus(), result.refundAmount());
    }
}
