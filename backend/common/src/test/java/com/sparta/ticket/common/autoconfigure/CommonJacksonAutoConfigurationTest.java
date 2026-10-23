package com.sparta.ticket.common.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

class CommonJacksonAutoConfigurationTest {

    enum Type { COMPANY, HUB }

    record Request(Type type) {
    }

    private final ObjectMapper objectMapper = build();

    private static ObjectMapper build() {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new CommonJacksonAutoConfiguration().strictEnumCustomizer().customize(builder);
        return builder.build();
    }

    @Test
    @DisplayName("Enum에 숫자를 보내면 선언 순서로 바꾸지 않고 거부한다")
    void rejectsNumericEnum() {
        assertThatThrownBy(() -> objectMapper.readValue("{\"type\":1}", Request.class))
                .isInstanceOf(InvalidFormatException.class);
    }

    @Test
    @DisplayName("문자열 Enum은 그대로 받는다")
    void acceptsStringEnum() throws Exception {
        assertThat(objectMapper.readValue("{\"type\":\"HUB\"}", Request.class).type()).isEqualTo(Type.HUB);
    }
}
