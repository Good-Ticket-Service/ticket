package com.sparta.ticket.performance.application.dto;

import java.util.List;

/** 등급별 가격 설정 결과(P11). 가격은 요청 순서대로다. */
public record GradePriceSet(List<Item> gradePrices, long performanceVersion) {

    public record Item(Long venueSeatGradeId, String gradeName, int price) {}
}
