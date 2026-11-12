package com.sparta.ticket.payment.infrastructure.messaging;

import com.sparta.ticket.payment.application.dto.payment.SettlementPostingCommand;
import com.sparta.ticket.payment.application.service.payment.InvalidSettlementPostingException;
import com.sparta.ticket.payment.domain.model.settlement.SettlementJobReason;
import java.time.LocalDateTime;
import java.util.UUID;

public record SettlementPostingEvent(
        int version,
        UUID eventId,
        long jobId,
        long paymentId,
        SettlementJobReason reason,
        LocalDateTime occurredAt,
        UUID replayCommandId) {
    public SettlementPostingCommand command() {
        if (version != 1) throw new InvalidSettlementPostingException("UNSUPPORTED_VERSION");
        return new SettlementPostingCommand(eventId, jobId, paymentId, reason, occurredAt, replayCommandId);
    }
}
