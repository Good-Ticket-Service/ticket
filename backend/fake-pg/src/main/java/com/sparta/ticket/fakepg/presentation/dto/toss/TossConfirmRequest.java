package com.sparta.ticket.fakepg.presentation.dto.toss;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 토스 결제 승인 요청 본문. */
public record TossConfirmRequest(
        @NotBlank String paymentKey,
        @NotBlank String orderId,
        @NotNull @Positive Integer amount) {}
