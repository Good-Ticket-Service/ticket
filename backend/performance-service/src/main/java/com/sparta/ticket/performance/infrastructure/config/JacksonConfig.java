package com.sparta.ticket.performance.infrastructure.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 공연 서비스의 JSON 읽기 규칙. 숫자는 JSON 정수 그대로만 받는다(이슈 #181).
 * Note: Jackson 기본값은 Long·Integer 필드에 온 1.9를 1로 자르고(ACCEPT_FLOAT_AS_INT), 문자열 "1"을 숫자로 바꾼다
 * (ALLOW_COERCION_OF_SCALARS). Bean Validation은 바뀐 값만 보므로 다른 ID·금액으로 처리돼도 400이 나지 않는다.
 * 필드마다 역직렬화기를 붙이면 새 DTO에서 빠뜨리기 쉬워 서비스 전체에 건다. 팀 공통 설정(CommonJacksonAutoConfiguration)은
 * 바꾸지 않고 이 서비스에만 더한다. 같은 ObjectMapper를 쓰는 Feign 응답 읽기에도 적용된다.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer strictNumberCustomizer() {
        return builder -> builder.featuresToDisable(
                DeserializationFeature.ACCEPT_FLOAT_AS_INT, MapperFeature.ALLOW_COERCION_OF_SCALARS);
    }
}
