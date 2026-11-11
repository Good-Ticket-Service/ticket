package com.sparta.ticket.payment.infrastructure.settlement.projection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.Serializer;

/** 앱·실제 프로세스 시험·Streams state가 같은 wire 형식을 사용한다. */
public final class ProjectionJson {
    private static final ObjectMapper JSON = new ObjectMapper()
            .findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private ProjectionJson() {}

    public static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("집계 사건 직렬화 실패", exception);
        }
    }

    public static <T> T read(String value, Class<T> type) {
        if (value == null) throw new IllegalArgumentException("집계 사건이 비어 있다");
        try {
            T result = JSON.readValue(value, type);
            if (result == null) throw new IllegalArgumentException("집계 사건이 비어 있다");
            return result;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("집계 사건 형식 오류", exception);
        }
    }

    public static <T> Serde<T> serde(Class<T> type) {
        Serializer<T> serializer =
                (topic, value) -> value == null ? null : write(value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Deserializer<T> deserializer = (topic, value) ->
                value == null ? null : read(new String(value, java.nio.charset.StandardCharsets.UTF_8), type);
        return Serdes.serdeFrom(serializer, deserializer);
    }
}
