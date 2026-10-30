package com.sparta.ticket.performance.application.service;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.performance.application.dto.NewSchedule;
import com.sparta.ticket.performance.application.dto.SellerScheduleDetail;
import com.sparta.ticket.performance.application.port.PerformanceStore;
import com.sparta.ticket.performance.application.port.ScheduleStore;
import com.sparta.ticket.performance.domain.performance.Performance;
import com.sparta.ticket.performance.domain.performance.PerformanceErrorCode;
import com.sparta.ticket.performance.domain.schedule.Schedule;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 판매자 회차 명령: 회차 등록. 명세는 .local/performance/api.md 「POST /api/seller/performances/{performanceId}/schedules」다.
 * 회차 저장은 트랜잭션 안에서, 좌석 준비 요청은 트랜잭션이 끝난 뒤 SeatSetupDispatcher가 한다. 응답은 등록 결과(PENDING)다.
 * Note: 메서드에 @Transactional을 붙이지 않고 TransactionTemplate으로 트랜잭션 범위를 직접 정한다. 좌석 호출이 트랜잭션 밖이라는 것을
 * 코드에서 바로 보이게 하려는 것이다. 다른 트랜잭션 안에서 부르면 안 된다(그 트랜잭션에 합류해 호출 동안 커넥션을 쥔다).
 */
@Service
@RequiredArgsConstructor
public class SellerScheduleService {

    private final PerformanceStore performanceStore;
    private final ScheduleStore scheduleStore;
    private final SeatSetupDispatcher seatSetupDispatcher;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public SellerScheduleDetail register(Long sellerId, Long performanceId, NewSchedule input) {
        SellerScheduleDetail schedule =
                transactionTemplate.execute(status -> registerInTransaction(sellerId, performanceId, input));
        seatSetupDispatcher.dispatch(List.of(schedule.id()));
        return schedule;
    }

    /**
     * 회차를 저장한다. If-Match는 받지 않고 부모 공연 행을 FOR UPDATE로 잠가 공개·가격 변경과 한 줄로 세운다.
     * Note: 확인 순서는 400 INVALID_TIME_RANGE → 404 → 403 → 409 INVALID_STATUS_TRANSITION → 409 DUPLICATE_SCHEDULE이다.
     * 시각 순서는 DB를 볼 필요 없는 입력 오류라 잠그기 전에 거절한다. 중복은 부모 공연을 잠근 뒤 확인하므로 부분 UNIQUE
     * uq_p_schedules_start 위반이 생기지 않아 409 변환을 두지 않는다. 삭제된 회차와 같은 시각이면 새 회차를 만든다.
     */
    private SellerScheduleDetail registerInTransaction(Long sellerId, Long performanceId, NewSchedule input) {
        Schedule.verifyTimeRange(input.startAt(), input.bookingOpenAt(), input.bookingCloseAt());

        Performance performance = performanceStore
                .findByIdForUpdate(performanceId)
                .orElseThrow(() -> new BusinessException(PerformanceErrorCode.PERFORMANCE_NOT_FOUND));
        performance.verifyOwner(sellerId);
        performance.verifyNotCanceled();
        if (scheduleStore.existsByPerformanceIdAndStartAt(performanceId, input.startAt())) {
            throw new BusinessException(PerformanceErrorCode.DUPLICATE_SCHEDULE);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        Schedule schedule = scheduleStore.save(Schedule.register(
                performanceId,
                input.startAt(),
                input.bookingOpenAt(),
                input.bookingCloseAt(),
                UUID.randomUUID().toString(),
                now));
        return SellerScheduleDetail.of(schedule, performance.getOperationStatus(), now);
    }
}
