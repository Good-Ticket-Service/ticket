package com.sparta.ticket.performance.presentation.support;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.performance.application.dto.PublicPerformanceStatus;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** status의 원시 반복 파라미터를 읽어 쉼표·중복을 정규화하고 명시적 빈 항목을 거절한다. */
public final class PerformanceStatusParameter {

    private PerformanceStatusParameter() {}

    public static Set<PublicPerformanceStatus> parse(List<String> values) {
        if (values == null) {
            return Set.of(PublicPerformanceStatus.ON_SALE, PublicPerformanceStatus.UPCOMING);
        }
        if (values.isEmpty()) {
            throw invalidStatus();
        }
        EnumSet<PublicPerformanceStatus> statuses = EnumSet.noneOf(PublicPerformanceStatus.class);
        for (String value : values) {
            if (value == null) {
                throw invalidStatus();
            }
            // Note: 마지막 빈 항목도 검증하도록 분할 결과의 끝 빈 문자열을 보존한다.
            for (String token : value.split(",", -1)) {
                String normalized = RequestText.stripToNull(token);
                if (normalized == null) {
                    throw invalidStatus();
                }
                try {
                    statuses.add(PublicPerformanceStatus.valueOf(normalized));
                } catch (IllegalArgumentException exception) {
                    throw invalidStatus();
                }
            }
        }
        return Set.copyOf(statuses);
    }

    private static BusinessException invalidStatus() {
        return new BusinessException(
                CommonErrorCode.VALIDATION_ERROR, "status는 ON_SALE, UPCOMING, ENDED 중 하나 이상이어야 하며 빈 항목을 포함할 수 없습니다.");
    }
}
