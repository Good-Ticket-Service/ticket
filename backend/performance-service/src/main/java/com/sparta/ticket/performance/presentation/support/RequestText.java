package com.sparta.ticket.performance.presentation.support;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;

/**
 * 요청 문자열의 앞뒤 공백을 지운다. 공연장·공연 변경 API와 {@link HttpsUrl} 검사가 같이 쓴다.
 * Note: 공백은 {@link #isBlank(int)} 한 가지 기준만 쓴다. String.strip()은 NBSP(U+00A0)를 지우지 않고, 정규식 \s는
 * 설정에 따라 범위가 달라진다. 검사와 정리가 다른 기준을 쓰면 검사를 통과한 값에 공백이 남는다(이슈 #129 R2).
 */
public final class RequestText {

    private RequestText() {}

    /**
     * 앞뒤 공백을 지우고, 남는 것이 없으면 400 VALIDATION_ERROR다.
     * Note: @NotBlank는 trim()(U+0020 이하만 제거) 기준이라 전각 공백(U+3000)·NBSP만 있는 값을 통과시킨다.
     * 여기서 다시 확인하지 않으면 빈 문자열이 저장되거나 DB CHECK 위반(500)이 된다.
     */
    public static String strip(String value) {
        String stripped = stripToNull(value);
        if (stripped == null) {
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR, "공백만 입력할 수 없습니다.");
        }
        return stripped;
    }

    /** 선택 항목용. null이거나 앞뒤 공백을 지우고 남는 것이 없으면 null(입력하지 않음)이다. */
    public static String stripToNull(String value) {
        if (value == null) {
            return null;
        }
        int start = 0;
        int end = value.length();
        while (start < end && isBlank(value.codePointAt(start))) {
            start += Character.charCount(value.codePointAt(start));
        }
        while (end > start && isBlank(value.codePointBefore(end))) {
            end -= Character.charCount(value.codePointBefore(end));
        }
        return start == end ? null : value.substring(start, end);
    }

    /** 공백이 하나라도 있는지. URL처럼 중간 공백도 허용하지 않는 값에 쓴다. */
    public static boolean containsBlank(String value) {
        return value.codePoints().anyMatch(RequestText::isBlank);
    }

    /** 제어 공백(탭·줄바꿈 등, Character.isWhitespace)과 유니코드 공백 문자(NBSP·전각 공백 등, Character.isSpaceChar). */
    public static boolean isBlank(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }
}
