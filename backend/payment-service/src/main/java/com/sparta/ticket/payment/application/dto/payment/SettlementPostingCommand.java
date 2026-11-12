package com.sparta.ticket.payment.application.dto.payment;

import com.sparta.ticket.payment.domain.model.settlement.SettlementJobReason;
import java.time.LocalDateTime;
import java.util.UUID;

/** 전송된 금액을 신뢰하지 않고 영속 작업의 동일성만 검증한다. */
public record SettlementPostingCommand(
        UUID eventId,
        long jobId,
        long paymentId,
        SettlementJobReason reason,
        LocalDateTime occurredAt,
        UUID replayCommandId) {
    public SettlementPostingCommand {
        if (eventId == null || jobId < 1 || paymentId < 1 || reason == null || occurredAt == null)
            throw new IllegalArgumentException("정산 사건 식별자가 유효하지 않다");
    }
}
