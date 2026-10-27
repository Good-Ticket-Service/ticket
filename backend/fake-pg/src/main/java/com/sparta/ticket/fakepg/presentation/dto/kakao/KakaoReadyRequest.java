package com.sparta.ticket.fakepg.presentation.dto.kakao;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** 카카오페이 결제 준비 요청. 필드 이름은 카카오처럼 snake_case다. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record KakaoReadyRequest(
        @NotBlank String cid,
        @NotBlank @Size(max = 64) String partnerOrderId,
        @NotBlank @Size(max = 100) String partnerUserId,
        @NotBlank @Size(max = 100) String itemName,
        @NotNull @Positive Integer quantity,
        @NotNull @Positive Integer totalAmount,
        @NotNull @PositiveOrZero Integer taxFreeAmount,
        @NotBlank String approvalUrl,
        @NotBlank String cancelUrl,
        @NotBlank String failUrl) {}
