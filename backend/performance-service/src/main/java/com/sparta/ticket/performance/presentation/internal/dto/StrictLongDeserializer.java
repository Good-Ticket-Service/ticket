package com.sparta.ticket.performance.presentation.internal.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;

/**
 * JSON 정수만 long으로 받는다. 소수(1.9)나 문자열("1")은 잘라내거나 변환하지 않고 거절해 공통 400 VALIDATION_ERROR가 되게 한다.
 * Note: Jackson 기본 설정은 1.9를 1로 잘라 받는다. 판매 검증에서 다른 좌석 ID·구성 버전으로 검증하는 일을 막으려고
 * 공통 설정을 바꾸지 않고 필요한 필드에만 붙인다. null은 이 클래스를 거치지 않으므로 @NotNull이 잡는다.
 */
public class StrictLongDeserializer extends JsonDeserializer<Long> {

    @Override
    public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
            return (Long) context.handleUnexpectedToken(Long.class, parser);
        }
        return parser.getLongValue();
    }
}
