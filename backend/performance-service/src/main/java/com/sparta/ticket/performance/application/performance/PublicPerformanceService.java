package com.sparta.ticket.performance.application.performance;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.performance.application.performance.dto.PublicPerformanceDetail;
import com.sparta.ticket.performance.application.performance.dto.PublicPerformancePage;
import com.sparta.ticket.performance.application.performance.dto.PublicPerformanceSearchCondition;
import com.sparta.ticket.performance.domain.performance.PerformanceErrorCode;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 일반 사용자용 공연 목록·상세 조회. 명세는 .local/performance/api.md 「공개 조회」다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PublicPerformanceService {

    private final PublicPerformanceReader reader;
    private final Clock clock;

    /** from이 to보다 늦으면 400 INVALID_TIME_RANGE. */
    public PublicPerformancePage search(PublicPerformanceSearchCondition condition) {
        if (condition.from() != null
                && condition.to() != null
                && condition.from().isAfter(condition.to())) {
            throw new BusinessException(PerformanceErrorCode.INVALID_TIME_RANGE);
        }
        return reader.search(condition, LocalDateTime.now(clock));
    }

    /** 초안·삭제·미존재 공연은 404 PERFORMANCE_NOT_FOUND. 취소된 공개 공연은 조회한다. */
    public PublicPerformanceDetail getDetail(Long performanceId) {
        return reader.findDetail(performanceId)
                .orElseThrow(() -> new BusinessException(PerformanceErrorCode.PERFORMANCE_NOT_FOUND));
    }
}
