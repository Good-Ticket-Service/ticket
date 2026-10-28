package com.sparta.ticket.performance.application.performance;

import com.sparta.ticket.performance.application.performance.dto.PublicPerformanceDetail;
import com.sparta.ticket.performance.application.performance.dto.PublicPerformancePage;
import com.sparta.ticket.performance.application.performance.dto.PublicPerformanceSearchCondition;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 일반 사용자용 공연 조회. 여러 테이블을 조인해 응답 모양으로 바로 읽는다. 구현은 infrastructure의 QueryDSL 저장소가 맡는다.
 * 공개(PUBLISHED)이고 삭제되지 않은 공연만 대상으로 한다.
 */
public interface PublicPerformanceReader {

    /**
     * 목록 검색. 취소된 공연은 넣지 않는다.
     *
     * @param now BOOKING_OPEN_ASC 정렬에서 예매가 마감된 회차를 빼는 기준 시각(KST)
     */
    PublicPerformancePage search(PublicPerformanceSearchCondition condition, LocalDateTime now);

    /** 상세. 취소된 공연도 취소 안내를 위해 돌려준다. */
    Optional<PublicPerformanceDetail> findDetail(Long performanceId);
}
