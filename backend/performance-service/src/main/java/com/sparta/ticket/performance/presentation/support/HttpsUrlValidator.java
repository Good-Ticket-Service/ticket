package com.sparta.ticket.performance.presentation.support;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** {@link HttpsUrl} 검사. 정리 결과가 null이 아니면 https:// 뒤에 한 글자 이상 있고 공백이 없어야 한다. */
public class HttpsUrlValidator implements ConstraintValidator<HttpsUrl, String> {

    private static final String SCHEME = "https://";

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        String url = RequestText.stripToNull(value);
        if (url == null) {
            return true;
        }
        return url.startsWith(SCHEME) && url.length() > SCHEME.length() && !RequestText.containsBlank(url);
    }
}
