package com.sparta.ticket.fakepg.presentation.dto.kakao;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/** 카카오페이 결제 취소 요청. 카카오는 취소 금액이 필수다. fake PG는 전액 취소만 받는다. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record KakaoCancelRequest(
        @NotBlank String cid,
        @NotBlank String tid,
        @NotNull @Positive Integer cancelAmount,
        @NotNull @PositiveOrZero Integer cancelTaxFreeAmount) {}
