package com.sparta.ticket.fakepg.presentation.dto.toss;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 토스 결제 취소 요청 본문. cancelAmount를 비우면 전액 취소다. 부분 취소는 지원하지 않는다.
 */
public record TossCancelRequest(
        @NotBlank String cancelReason, @Positive Integer cancelAmount) {}
