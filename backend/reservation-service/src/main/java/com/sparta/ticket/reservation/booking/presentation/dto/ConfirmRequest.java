package com.sparta.ticket.reservation.booking.presentation.dto;

import com.sparta.ticket.reservation.booking.application.dto.ConfirmCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 결제 승인 요청. pgAuthToken은 결제창 인증 뒤 PG가 준 값(토스 paymentKey, 카카오 pg_token)이다. 금액 필드를 두지 않는다. */
public record ConfirmRequest(
        @NotBlank @Size(max = 64) String orderId,
        @NotBlank @Size(max = 255) String pgAuthToken) {

    public ConfirmCommand toCommand() {
        return new ConfirmCommand(orderId, pgAuthToken);
    }
}
