package com.sparta.ticket.fakepg.presentation.dto.kakao;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.sparta.ticket.fakepg.domain.model.transaction.PgTransaction;

/** 카카오 사용자 승인 시뮬레이션 결과. 실제 카카오에서는 결제창 인증 후 approval_url의 쿼리(pg_token)로 받는 값이다. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record KakaoUserApprovalResponse(String tid, String partnerOrderId, String pgToken) {

    public static KakaoUserApprovalResponse from(PgTransaction transaction) {
        return new KakaoUserApprovalResponse(
                transaction.getPaymentKey(), transaction.getOrderId(), transaction.getPgToken());
    }
}
