package com.sparta.ticket.performance.application.service;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.performance.application.dto.SalesSuspension;
import com.sparta.ticket.performance.application.port.PerformanceStore;
import com.sparta.ticket.performance.application.port.ScheduleStore;
import com.sparta.ticket.performance.domain.performance.OperationStatus;
import com.sparta.ticket.performance.domain.performance.PerformanceErrorCode;
import com.sparta.ticket.performance.domain.schedule.Schedule;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 관리자 회차 명령: 판매 중지·재개. 명세는 .local/performance/api.md 「PUT /api/admin/schedules/{scheduleId}/sales-suspension」다. */
@Service
@RequiredArgsConstructor
public class AdminScheduleService {

    private final ScheduleStore scheduleStore;
    private final PerformanceStore performanceStore;

    /**
     * 회차의 신규 판매를 멈추거나 다시 연다. 이미 잡힌 HOLD와 진행 중인 결제에는 영향이 없다(판매 검증이 신규 선점에만 쓰인다).
     * Note: 확인 순서는 404 → 409 INVALID_STATUS_TRANSITION(취소된 회차·공연) → 409 VERSION_CONFLICT다(이슈 #133 결정 4).
     * 회차 행만 FOR UPDATE로 잠그고 부모 공연은 잠그지 않는다. 공연의 운영 상태를 바꾸는 API가 없어서다. 회차 행 잠금으로 좌석 준비
     * 진행(같은 행 잠금)과 차례로 처리된다. 결과가 지금과 같으면 저장하지 않아 version이 그대로다.
     */
    @Transactional
    public SalesSuspension changeSalesSuspension(
            Long scheduleId, long expectedVersion, boolean suspended, String reason) {
        Schedule schedule = scheduleStore
                .findByIdForUpdate(scheduleId)
                .orElseThrow(() -> new BusinessException(PerformanceErrorCode.SCHEDULE_NOT_FOUND));
        OperationStatus performanceStatus = performanceStore
                .findOperationStatus(schedule.getPerformanceId())
                .orElseThrow(() -> new BusinessException(PerformanceErrorCode.SCHEDULE_NOT_FOUND));
        schedule.verifySalesChangeable(performanceStatus);
        schedule.verifyVersion(expectedVersion);

        if (schedule.changeSalesSuspension(suspended, reason)) {
            schedule = scheduleStore.saveAndFlush(schedule);
        }
        return SalesSuspension.of(schedule);
    }
}
