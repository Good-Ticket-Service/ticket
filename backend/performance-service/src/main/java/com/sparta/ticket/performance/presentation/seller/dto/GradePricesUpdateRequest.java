package com.sparta.ticket.performance.presentation.seller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

/**
 * 등급별 가격 설정 요청(5.4). 공연의 가격 집합 전체이며 빈 배열도 받는다.
 * Note: 빈 배열은 판매 대상 좌석이 없을 때만 통과한다. 그 밖에는 서비스가 400 GRADE_PRICE_MISSING으로 거절한다.
 * 등급 ID와 가격은 JSON 정수만 받는다(서비스 전체 설정 JacksonConfig). 소수를 잘라 받으면 다른 등급·금액으로 저장된다.
 */
public record GradePricesUpdateRequest(@NotNull List<@NotNull @Valid GradePrice> gradePrices) {

    public record GradePrice(
            @NotNull @Positive Long venueSeatGradeId,
            @NotNull @Positive Integer price) {}
}
