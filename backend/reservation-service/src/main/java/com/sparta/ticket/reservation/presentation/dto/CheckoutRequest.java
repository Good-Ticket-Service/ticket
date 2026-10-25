package com.sparta.ticket.reservation.presentation.dto;

import com.sparta.ticket.reservation.application.dto.CheckoutCommand;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

/** 결제 준비 요청. 금액 필드를 두지 않는다. pgProvider 값은 payment 계약(P1)을 따른다. */
public record CheckoutRequest(UUID couponId, @NotBlank String pgProvider) {

    public CheckoutCommand toCommand() {
        return new CheckoutCommand(couponId, pgProvider);
    }
}
