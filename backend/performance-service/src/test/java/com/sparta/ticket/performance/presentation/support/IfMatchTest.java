package com.sparta.ticket.performance.presentation.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.performance.domain.performance.PerformanceErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** If-Match 헤더 해석 규칙. */
class IfMatchTest {

    @Test
    @DisplayName("큰따옴표로 감싼 숫자를 version으로 읽는다")
    void quoted() {
        assertThat(IfMatch.parse("\"3\"")).isEqualTo(3L);
        assertThat(IfMatch.parse(" \"0\" ")).isZero();
    }

    @Test
    @DisplayName("따옴표 없는 숫자도 받는다")
    void unquoted() {
        assertThat(IfMatch.parse("12")).isEqualTo(12L);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("헤더가 없거나 비었으면 428 PRECONDITION_REQUIRED")
    void missing(String header) {
        assertThatThrownBy(() -> IfMatch.parse(header))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PerformanceErrorCode.PRECONDITION_REQUIRED));
    }

    @ParameterizedTest
    @ValueSource(strings = {"W/\"3\"", "*", "\"3", "3\"", "\"abc\"", "-1", "\"1\", \"2\"", "1234567890123456789"})
    @DisplayName("형식이 틀리면 400 VALIDATION_ERROR")
    void malformed(String header) {
        assertThatThrownBy(() -> IfMatch.parse(header))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.VALIDATION_ERROR));
    }
}
