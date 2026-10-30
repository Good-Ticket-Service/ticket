package com.sparta.ticket.performance.presentation.internal.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.sparta.ticket.performance.presentation.support.StrictLongDeserializer;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.HashSet;
import java.util.List;

/**
 * 판매 검증 요청. 위반하면 공통 400 VALIDATION_ERROR다.
 * 두 필드 모두 JSON 정수만 받는다(소수·문자열 거절, {@link StrictLongDeserializer}).
 *
 * @param configVersion 좌석 서비스가 가진 회차 좌석 구성 버전. 1 이상
 * @param venueSeatIds 선점하려는 공연장 좌석 ID. 1개 이상, 중복 불가
 */
public record SaleValidationRequest(
        @NotNull @Min(1) @JsonDeserialize(using = StrictLongDeserializer.class)
        Long configVersion,

        @NotEmpty @JsonDeserialize(contentUsing = StrictLongDeserializer.class)
        List<@NotNull Long> venueSeatIds) {

    /** 같은 좌석을 두 번 보내면 거절한다. 목록이 비었거나 null이면 @NotEmpty가 따로 잡는다. */
    @JsonIgnore
    @AssertTrue(message = "venueSeatIds에 중복이 없어야 합니다.")
    public boolean isVenueSeatIdsDistinct() {
        return venueSeatIds == null || new HashSet<>(venueSeatIds).size() == venueSeatIds.size();
    }
}
