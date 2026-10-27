package com.sparta.ticket.fakepg.presentation.dto.kakao;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.sparta.ticket.fakepg.domain.model.transaction.PgTransaction;
import java.time.LocalDateTime;

/**
 * 카카오페이 결제 준비 응답. 결제창 URL은 실제로 열 수 있는 페이지가 아니다. fake PG에서는 사용자 승인 단계를
 * {@code POST /fake/kakao/approve}가 대신한다. 시각은 카카오처럼 오프셋 없는 한국 시간이다.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record KakaoReadyResponse(
        String tid,
        String nextRedirectAppUrl,
        String nextRedirectMobileUrl,
        String nextRedirectPcUrl,
        String androidAppScheme,
        String iosAppScheme,
        LocalDateTime createdAt) {

    public static KakaoReadyResponse from(PgTransaction transaction, String checkoutUrlBase) {
        String url = checkoutUrlBase + "/fake/kakao/checkout/" + transaction.getPaymentKey();
        return new KakaoReadyResponse(
                transaction.getPaymentKey(), url, url, url, url, url, transaction.getRequestedAt());
    }
}
