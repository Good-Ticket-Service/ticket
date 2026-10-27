package com.sparta.ticket.fakepg.presentation.dto.kakao;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;

/** 카카오페이 주문 조회 요청. 카카오는 주문번호가 아니라 tid로만 조회한다. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record KakaoOrderRequest(
        @NotBlank String cid, @NotBlank String tid) {}
