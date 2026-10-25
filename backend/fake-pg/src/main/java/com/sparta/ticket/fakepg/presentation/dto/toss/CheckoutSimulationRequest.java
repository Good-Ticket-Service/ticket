package com.sparta.ticket.fakepg.presentation.dto.toss;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 결제 인증 시뮬레이션 요청. 브라우저가 토스 결제창에 넘기는 값과 같다.
 */
public record CheckoutSimulationRequest(
        @NotBlank @Size(max = 64) String orderId,
        @NotNull @Positive Integer amount,
        @Size(max = 100) String orderName) {}
