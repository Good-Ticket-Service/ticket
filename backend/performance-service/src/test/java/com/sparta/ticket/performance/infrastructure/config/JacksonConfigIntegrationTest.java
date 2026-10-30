package com.sparta.ticket.performance.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.sparta.ticket.performance.domain.performance.Genre;
import com.sparta.ticket.performance.support.PerformanceIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 공연 서비스 ObjectMapper(요청 본문·Feign 응답 읽기에 쓰는 것)가 숫자를 JSON 정수 그대로만 받는지 확인한다. */
class JacksonConfigIntegrationTest extends PerformanceIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    record Numbers(Long id, Integer price, List<Long> ids) {}

    record Flags(Boolean suspended) {}

    record Text(String title) {}

    record Category(Genre genre) {}

    @Test
    @DisplayName("JSON 정수는 Long·Integer·Long 목록으로 그대로 받는다")
    void readsIntegers() throws Exception {
        Numbers numbers = objectMapper.readValue("{\"id\":301,\"price\":150000,\"ids\":[1,2]}", Numbers.class);

        assertThat(numbers).isEqualTo(new Numbers(301L, 150000, List.of(1L, 2L)));
    }

    @Test
    @DisplayName("소수는 잘라 받지 않고 거절한다")
    void rejectsFloats() {
        for (String json : new String[] {"{\"id\":301.9}", "{\"price\":1.9}", "{\"ids\":[1,2.5]}", "{\"id\":1.0}"}) {
            assertThatThrownBy(() -> objectMapper.readValue(json, Numbers.class))
                    .as(json)
                    .isInstanceOf(MismatchedInputException.class);
        }
    }

    @Test
    @DisplayName("문자열 숫자·문자열 boolean은 바꿔 받지 않고 거절한다")
    void rejectsStringScalars() {
        for (String json : new String[] {"{\"id\":\"301\"}", "{\"price\":\"150000\"}", "{\"ids\":[\"1\"]}"}) {
            assertThatThrownBy(() -> objectMapper.readValue(json, Numbers.class))
                    .as(json)
                    .isInstanceOf(MismatchedInputException.class);
        }
        assertThatThrownBy(() -> objectMapper.readValue("{\"suspended\":\"true\"}", Flags.class))
                .isInstanceOf(MismatchedInputException.class);
    }

    @Test
    @DisplayName("int 범위를 넘는 정수는 Integer로 받지 않는다")
    void rejectsIntOverflow() {
        assertThatThrownBy(() -> objectMapper.readValue("{\"price\":3000000000}", Numbers.class))
                .isInstanceOf(JsonMappingException.class)
                .hasMessageContaining("out of range of int");
    }

    @Test
    @DisplayName("문자열 필드에 온 숫자는 지금처럼 문자열로 받는다(이 설정의 범위 밖)")
    void stillReadsNumberAsString() throws Exception {
        // Note: ALLOW_COERCION_OF_SCALARS를 꺼도 숫자→문자열은 막히지 않는다. 문자열 필드는 @Size·@Pattern 등이 값을 검사한다
        assertThat(objectMapper.readValue("{\"title\":123}", Text.class)).isEqualTo(new Text("123"));
    }

    @Test
    @DisplayName("팀 공통 설정의 enum 숫자 거절도 함께 유지된다. 이름 문자열만 받는다")
    void keepsCommonEnumRule() throws Exception {
        // Note: 공통 CommonJacksonAutoConfiguration(FAIL_ON_NUMBERS_FOR_ENUMS)과 JacksonConfig가 함께 적용되는지 확인한다
        assertThat(objectMapper.readValue("{\"genre\":\"MUSICAL\"}", Category.class))
                .isEqualTo(new Category(Genre.MUSICAL));
        assertThatThrownBy(() -> objectMapper.readValue("{\"genre\":0}", Category.class))
                .isInstanceOf(MismatchedInputException.class);
    }
}
