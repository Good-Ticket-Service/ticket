package com.sparta.ticket.waitingroom.infrastructure.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * JSON 읽기 규칙. 숫자와 boolean은 JSON 값 그대로만 받는다(이슈 #233). reservation-service에서 옮겨 올 때 같은 규칙을 유지한다.
 * Note: Jackson 기본값은 Long·Integer 필드에 온 5.9를 5로 자르고 문자열 "6"·"true"를 숫자·boolean으로 바꾼다.
 * Bean Validation은 바뀐 값만 보므로 다른 값으로 처리돼도 400이 나지 않는다. 팀 공통 설정은 바꾸지 않고 이 서비스에만 더한다.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer strictScalarCustomizer() {
        return builder -> builder.featuresToDisable(
                DeserializationFeature.ACCEPT_FLOAT_AS_INT, MapperFeature.ALLOW_COERCION_OF_SCALARS);
    }
}
