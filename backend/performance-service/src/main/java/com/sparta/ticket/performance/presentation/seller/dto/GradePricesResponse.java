package com.sparta.ticket.performance.presentation.seller.dto;

import com.sparta.ticket.performance.application.dto.GradePriceSet;
import java.util.List;

/** 등급별 가격 설정 응답(P11). performanceVersion은 다음 공연 단위 명령의 If-Match에 쓴다. */
public record GradePricesResponse(List<GradePrice> gradePrices, long performanceVersion) {

    public record GradePrice(Long venueSeatGradeId, String gradeName, int price) {}

    public static GradePricesResponse from(GradePriceSet result) {
        return new GradePricesResponse(
                result.gradePrices().stream()
                        .map(item -> new GradePrice(item.venueSeatGradeId(), item.gradeName(), item.price()))
                        .toList(),
                result.performanceVersion());
    }
}
