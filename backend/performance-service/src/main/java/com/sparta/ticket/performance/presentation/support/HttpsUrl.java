package com.sparta.ticket.performance.presentation.support;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 선택 항목 HTTPS URL. null·빈 값·공백만이면 통과(입력하지 않음)하고, 그 밖에는 앞뒤 공백을 지운 값이 https://로 시작하고
 * 공백이 없어야 한다. 공백 기준은 Api가 값을 정리할 때 쓰는 {@link RequestText}와 같다.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = HttpsUrlValidator.class)
public @interface HttpsUrl {

    String message() default "HTTPS URL이어야 합니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
