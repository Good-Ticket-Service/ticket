package com.sparta.ticket.performance.presentation.support;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;

/** 요청 문자열의 앞뒤 공백을 지운다. 공연장·공연 변경 API가 같이 쓴다. */
public final class RequestText {

    private RequestText() {}

    /**
     * 앞뒤 공백을 지우고, 남는 것이 없으면 400 VALIDATION_ERROR다.
     * Note: @NotBlank는 trim()(U+0020 이하만 제거) 기준이라 전각 공백(U+3000)만 있는 값을 통과시킨다.
     * strip()은 유니코드 공백까지 지우므로 여기서 다시 확인하지 않으면 빈 문자열이 저장되거나 DB CHECK 위반(500)이 된다.
     */
    public static String strip(String value) {
        String stripped = value.strip();
        if (stripped.isEmpty()) {
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR, "공백만 입력할 수 없습니다.");
        }
        return stripped;
    }
}
