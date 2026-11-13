package com.sparta.ticket.payment.presentation.dto.internal;

import com.sparta.ticket.payment.application.dto.payment.ConfirmCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;

public record ConfirmRequest(
        @NotBlank String pgAuthToken, @NotNull Integer amount, OffsetDateTime confirmBefore) {
    public ConfirmCommand toCommand() {
        return new ConfirmCommand(pgAuthToken, amount, confirmBefore);
    }

    @Override
    public String toString() {
        return "ConfirmRequest[pgAuthToken=REDACTED, amount=" + amount + ", confirmBefore=" + confirmBefore + "]";
    }
}
