package com.sparta.ticket.common.autoconfigure;

import com.fasterxml.jackson.databind.DeserializationFeature;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/** 끄기: {@code common.jackson.enabled=false} */
@AutoConfiguration
@ConditionalOnClass(Jackson2ObjectMapperBuilder.class)
@ConditionalOnProperty(prefix = "common.jackson", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CommonJacksonAutoConfiguration {

    /**
     * Note: Jackson은 기본적으로 Enum에 숫자를 넣으면 선언 순서(ordinal)로 바꿔 준다. {@code "role": 0}이 MASTER가 되는 식이다.
     * Enum 선언 순서가 바뀌면 같은 숫자가 다른 값이 되므로 문자열만 받는다.
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer strictEnumCustomizer() {
        return builder -> builder.featuresToEnable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS);
    }
}
