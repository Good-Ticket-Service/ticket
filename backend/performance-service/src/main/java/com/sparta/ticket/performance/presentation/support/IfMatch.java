package com.sparta.ticket.performance.presentation.support;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.performance.domain.performance.PerformanceErrorCode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * If-Match 헤더를 version 숫자로 바꾼다. 공연장·공연·회차 변경 API가 같이 쓴다.
 * 현재 version과의 비교(409 VERSION_CONFLICT)는 DB 값이 필요하므로 application이 한다.
 */
public final class IfMatch {

    public static final String HEADER = "If-Match";

    /**
     * Note: 계약 형식은 "3"(큰따옴표 포함)이다. Swagger 등에서 손으로 넣기 쉽도록 따옴표 없는 3도 받는다.
     * 약한 비교(W/"3"), 와일드카드(*), 여러 값은 받지 않는다. 18자리 제한은 long 범위를 넘지 않게 하려는 것이다.
     */
    private static final Pattern FORMAT = Pattern.compile("^(?:\"(\\d{1,18})\"|(\\d{1,18}))$");

    private IfMatch() {}

    /** 없거나 빈 값이면 428 PRECONDITION_REQUIRED, 형식이 틀리면 400 VALIDATION_ERROR다. */
    public static long parse(String header) {
        if (header == null || header.isBlank()) {
            throw new BusinessException(PerformanceErrorCode.PRECONDITION_REQUIRED);
        }
        Matcher matcher = FORMAT.matcher(header.strip());
        if (!matcher.matches()) {
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR, "If-Match 형식이 올바르지 않습니다. 예: \"3\"");
        }
        String digits = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        return Long.parseLong(digits);
    }
}
