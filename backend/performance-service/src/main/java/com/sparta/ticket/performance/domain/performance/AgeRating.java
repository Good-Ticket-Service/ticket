package com.sparta.ticket.performance.domain.performance;

import java.util.Arrays;

/**
 * 관람 등급. DB와 API에는 code("ALL", "12", "15", "19")로 표현한다.
 * Note: 숫자로 시작하는 이름은 Java 상수로 쓸 수 없어 상수 이름과 저장 값을 나눈다. 저장 변환은 AgeRatingConverter가 맡는다.
 */
public enum AgeRating {
    ALL("ALL"),
    AGE_12("12"),
    AGE_15("15"),
    AGE_19("19");

    private final String code;

    AgeRating(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static AgeRating fromCode(String code) {
        return Arrays.stream(values())
                .filter(rating -> rating.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("알 수 없는 관람 등급입니다: " + code));
    }
}
