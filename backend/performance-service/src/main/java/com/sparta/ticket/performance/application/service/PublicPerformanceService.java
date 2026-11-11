package com.sparta.ticket.performance.application.service;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.performance.application.dto.PublicPerformanceDetail;
import com.sparta.ticket.performance.application.dto.PublicPerformancePage;
import com.sparta.ticket.performance.application.dto.PublicPerformanceSearchCondition;
import com.sparta.ticket.performance.application.port.PublicPerformanceReader;
import com.sparta.ticket.performance.domain.performance.PerformanceErrorCode;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 일반 사용자용 공연 목록·상세 조회. 명세는 .local/performance/api.md 「공개 조회」다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PublicPerformanceService {

    private final PublicPerformanceReader reader;
    private final Clock clock;

    /**
     * from이 to보다 늦으면 400 INVALID_TIME_RANGE.
     * Note: 목록은 페이지 ID·표시 정보와 기간·전체 개수를 쿼리 여러 개로 읽는다. 그 사이에 커밋된 변경이 섞여 한 응답 안에서
     * 항목이 빠지거나 개수가 어긋나지 않도록 REPEATABLE READ로 같은 시점을 읽는다(이슈 #343).
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
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
