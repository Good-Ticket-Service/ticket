package com.sparta.ticket.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 서비스(MVC)와 gateway(WebFlux)가 같은 ErrorResponse를 쓰므로, 공통 에러 형식을 여기서 한 번 고정한다. */
class ErrorResponseContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    @DisplayName("에러 응답은 status, code, message, path, timestamp를 갖고 errors는 넣지 않는다")
    void errorShape() throws Exception {
        JsonNode json = objectMapper.valueToTree(ErrorResponse.of(
                CommonErrorCode.TOKEN_ROLE_MISMATCH, CommonErrorCode.TOKEN_ROLE_MISMATCH.message(), "/api/v1/events"));

        assertThat(json.get("status").asInt()).isEqualTo(401);
        assertThat(json.get("code").asText()).isEqualTo("TOKEN_ROLE_MISMATCH");
        assertThat(json.get("path").asText()).isEqualTo("/api/v1/events");
        assertThat(json.get("timestamp").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");
        assertThat(json.has("errors")).isFalse();
    }

    @Test
    @DisplayName("VALIDATION_ERROR는 필드별 errors를 담는다")
    void validationShape() {
        JsonNode json = objectMapper.valueToTree(ErrorResponse.of(
                CommonErrorCode.VALIDATION_ERROR,
                "요청 값이 올바르지 않습니다.",
                "/api/v1/auth/signup",
                List.of(new ErrorResponse.FieldError("username", "형식 오류"))));

        assertThat(json.get("errors").get(0).get("field").asText()).isEqualTo("username");
        assertThat(json.get("errors").get(0).get("reason").asText()).isEqualTo("형식 오류");
    }
}
