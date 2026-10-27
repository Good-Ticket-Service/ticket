package com.sparta.ticket.payment.infrastructure.pg;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.function.Supplier;

/** PG 응답 본문 해석 도우미. 해석에 실패해도 예외를 던지지 않고 비운다. 어댑터가 예외를 던지면 호출한 흐름이 PG 결과를 잃는다. */
public final class PgJson {

    private PgJson() {}

    /** JSON 객체로 해석되면 그 노드, 아니면(빈 본문, HTML 오류 페이지 등) 비운다. */
    public static Optional<JsonNode> parse(ObjectMapper objectMapper, String body) {
        try {
            return Optional.ofNullable(objectMapper.readTree(body)).filter(JsonNode::isObject);
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    /** 응답 필드 변환(날짜 형식 등)이 실패하면 비운다. */
    public static <T> Optional<T> safely(Supplier<T> mapping) {
        try {
            return Optional.ofNullable(mapping.get());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
