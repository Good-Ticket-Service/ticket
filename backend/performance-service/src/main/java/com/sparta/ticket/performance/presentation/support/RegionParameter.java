package com.sparta.ticket.performance.presentation.support;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.performance.domain.venue.Region;

/** 선택 지역 필터. 기존 빈 입력은 미적용으로 두고, 입력한 값은 코드만 받는다. */
public final class RegionParameter {

    private RegionParameter() {}

    public static Region parseOptional(String value) {
        String normalized = RequestText.stripToNull(value);
        if (normalized == null) {
            return null;
        }
        try {
            return Region.valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR, "region은 허용된 시·도 코드여야 합니다.");
        }
    }
}
