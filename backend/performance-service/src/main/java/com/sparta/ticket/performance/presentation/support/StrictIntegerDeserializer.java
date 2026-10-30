package com.sparta.ticket.performance.presentation.support;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;

/**
 * JSON 정수만 int로 받는다. 소수(1.9)나 문자열("1")은 거절해 공통 400 VALIDATION_ERROR가 되게 한다.
 * int 범위를 넘는 정수도 Jackson이 거절한다. 가격·공연 시간처럼 잘려 들어가면 다른 값으로 저장되는 필드에 붙인다.
 * 이유와 쓰는 방식은 {@link StrictLongDeserializer}와 같다.
 */
public class StrictIntegerDeserializer extends JsonDeserializer<Integer> {

    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
            return (Integer) context.handleUnexpectedToken(Integer.class, parser);
        }
        return parser.getIntValue();
    }
}
