package com.sparta.ticket.performance.presentation.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 요청 문자열 공백 정리 규칙. */
class RequestTextTest {

    @Test
    @DisplayName("앞뒤 공백을 지운다. 전각 공백도 지운다")
    void strips() {
        assertThat(RequestText.strip(" VIP ")).isEqualTo("VIP");
        assertThat(RequestText.strip("　서울 공연장　")).isEqualTo("서울 공연장");
    }

    @Test
    @DisplayName("NBSP(U+00A0)·줄바꿈도 공백으로 보고 지운다. 가운데 공백은 남긴다")
    void stripsUnicodeSpaces() {
        assertThat(RequestText.strip(" VIP \n")).isEqualTo("VIP");
        assertThat(RequestText.stripToNull(" 　 ")).isNull();
        assertThat(RequestText.strip(" 서울 공연장 ")).isEqualTo("서울 공연장");
    }

    @Test
    @DisplayName("containsBlank는 가운데의 NBSP·전각 공백도 찾는다")
    void findsBlankInside() {
        assertThat(RequestText.containsBlank("https://a b.com")).isTrue();
        assertThat(RequestText.containsBlank("https://a　b.com")).isTrue();
        assertThat(RequestText.containsBlank("https://example.com/a.jpg")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "　", "　 \t", " ", " 　"})
    @DisplayName("지우고 남는 것이 없으면 400 VALIDATION_ERROR다")
    void rejectsBlank(String value) {
        assertThatThrownBy(() -> RequestText.strip(value))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.VALIDATION_ERROR));
    }
}
