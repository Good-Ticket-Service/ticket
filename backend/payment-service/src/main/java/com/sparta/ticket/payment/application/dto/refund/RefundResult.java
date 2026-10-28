package com.sparta.ticket.payment.application.dto.refund;

import com.sparta.ticket.payment.domain.model.refund.Refund;

public record RefundResult(String refundStatus, int refundAmount) {
    public static RefundResult from(Refund refund) {
        return new RefundResult(refund.getStatus().name(), refund.getRefundAmount());
    }

    public static RefundResult notRequired() {
        return new RefundResult("NOT_REQUIRED", 0);
    }
}
