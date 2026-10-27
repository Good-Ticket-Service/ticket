package com.sparta.ticket.fakepg.presentation.dto.kakao;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;

/** 카카오페이 결제 승인 요청. 금액은 보내지 않고, 사용자 승인 결과인 pg_token을 보낸다. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record KakaoApproveRequest(
        @NotBlank String cid,
        @NotBlank String tid,
        @NotBlank String partnerOrderId,
        @NotBlank String partnerUserId,
        @NotBlank String pgToken) {}
