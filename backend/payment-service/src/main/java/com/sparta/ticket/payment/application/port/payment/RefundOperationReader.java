package com.sparta.ticket.payment.application.port.payment;

import com.sparta.ticket.payment.domain.model.pg.PgProvider;
import com.sparta.ticket.payment.domain.model.refund.RefundRecoveryMode;
import com.sparta.ticket.payment.domain.model.refund.RefundStatus;
import java.time.LocalDateTime;
import java.util.List;

public interface RefundOperationReader {
    List<Long> paymentIds(
            long afterRefundId,
            int limit,
            RefundStatus status,
            RefundRecoveryMode mode,
            PgProvider provider,
            LocalDateTime createdBefore);
}
