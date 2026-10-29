package com.sparta.ticket.performance.application;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.performance.application.dto.PublicSchedule;
import com.sparta.ticket.performance.application.dto.PublicScheduleRow;
import com.sparta.ticket.performance.domain.performance.OperationStatus;
import com.sparta.ticket.performance.domain.performance.PerformanceErrorCode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 일반 사용자용 회차 목록·상세 조회. 판매 상태는 서버 시각으로 계산한다. 명세는 .local/performance/api.md 「공개 조회」다.
 * 부모 공연이 공개 공연인지 먼저(또는 회차를 찾은 뒤) 한 번 확인하고, 회차는 회차 테이블만 읽는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PublicScheduleService {

    private final PublicScheduleReader reader;
    private final Clock clock;

    /** 초안·삭제·미존재 공연은 404 PERFORMANCE_NOT_FOUND. 회차가 없으면 빈 목록이다. */
    public List<PublicSchedule> getSchedules(Long performanceId) {
        OperationStatus performanceStatus = reader.findPublicPerformanceStatus(performanceId)
                .orElseThrow(() -> new BusinessException(PerformanceErrorCode.PERFORMANCE_NOT_FOUND));
        LocalDateTime now = LocalDateTime.now(clock);
        return reader.findSchedules(performanceId).stream()
                .map(row -> PublicSchedule.of(row, performanceStatus, now))
                .toList();
    }

    /** 회차가 없거나 삭제됐거나, 부모 공연이 초안·삭제됐으면 404 SCHEDULE_NOT_FOUND. */
    public PublicSchedule getSchedule(Long scheduleId) {
        PublicScheduleRow row = reader.findSchedule(scheduleId)
                .orElseThrow(() -> new BusinessException(PerformanceErrorCode.SCHEDULE_NOT_FOUND));
        OperationStatus performanceStatus = reader.findPublicPerformanceStatus(row.performanceId())
                .orElseThrow(() -> new BusinessException(PerformanceErrorCode.SCHEDULE_NOT_FOUND));
        return PublicSchedule.of(row, performanceStatus, LocalDateTime.now(clock));
    }
}
