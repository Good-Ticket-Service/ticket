package com.sparta.ticket.payment.presentation.dto.internal;

import com.sparta.ticket.payment.application.dto.payment.ConfirmCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ConfirmRequest(
        @NotBlank String pgAuthToken, @NotNull Integer amount) {
    public ConfirmCommand toCommand() {
        return new ConfirmCommand(pgAuthToken, amount);
    }

    @Override
    public String toString() {
        return "ConfirmRequest[pgAuthToken=REDACTED, amount=" + amount + "]";
    }
}
