package com.sparta.ticket.performance.presentation.seller.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.sparta.ticket.performance.presentation.support.HttpsUrl;
import com.sparta.ticket.performance.presentation.support.StrictIntegerDeserializer;
import com.sparta.ticket.performance.presentation.support.StrictLongDeserializer;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 공연 초안 등록 요청(5.3). 알 수 없는 필드와 sellerId는 무시한다(판매자는 인증 주체).
 * Note: genre·ageRating은 문자열로 받아 형식을 검사한다. ageRating 값("12")이 Java 상수 이름과 달라 enum으로 바로 받을 수 없고,
 * 문자열 검사여야 잘못된 값이 필드 단위 400 VALIDATION_ERROR가 된다.
 * venueId·runningTime은 JSON 정수만 받는다(소수를 잘라 다른 공연장으로 등록하지 않게).
 */
public record PerformanceCreateRequest(
        @NotNull @Positive @JsonDeserialize(using = StrictLongDeserializer.class)
        Long venueId,

        @NotBlank @Size(max = 200) String title,

        @NotNull @Pattern(regexp = "CONCERT|MUSICAL|PLAY|SPORTS")
        String genre,

        @NotNull @Pattern(regexp = "ALL|12|15|19") String ageRating,

        @NotNull @Min(1) @JsonDeserialize(using = StrictIntegerDeserializer.class)
        Integer runningTime,

        @Size(max = 500) String castInfo,
        String description,

        /*
         * Note: 선택 항목이라 비었거나 공백만이면 통과시키고 Api가 null로 정리한다. 이 검사가 공백 정리보다 먼저 실행되므로,
         * 정규식 대신 정리와 같은 공백 기준(RequestText)을 쓰는 @HttpsUrl로 검사한다(이슈 #129 R2).
         */
        @Size(max = 500) @HttpsUrl String posterUrl) {}
