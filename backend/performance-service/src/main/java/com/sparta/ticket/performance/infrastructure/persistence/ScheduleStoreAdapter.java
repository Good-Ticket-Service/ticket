package com.sparta.ticket.performance.infrastructure.persistence;

import com.sparta.ticket.performance.application.dto.PerformancePeriod;
import com.sparta.ticket.performance.application.port.ScheduleStore;
import com.sparta.ticket.performance.domain.performance.OperationStatus;
import com.sparta.ticket.performance.domain.schedule.Schedule;
import com.sparta.ticket.performance.domain.schedule.SeatSetupStatus;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;

/**
 * ScheduleStore의 구현. 좌석 준비 필드 저장(version을 올리지 않는 JPQL UPDATE)과 개수 제한(Limit, Spring 타입)을 포트에 드러내지
 * 않으려고 어댑터를 둔다.
 */
@Repository
@RequiredArgsConstructor
public class ScheduleStoreAdapter implements ScheduleStore {

    private static final String SYSTEM = "SYSTEM";

    private final ScheduleJpaRepository scheduleRepository;
    private final EntityManager entityManager;
    private final AuditorAware<String> auditorAware;

    @Override
    public Schedule save(Schedule schedule) {
        return scheduleRepository.save(schedule);
    }

    @Override
    public Optional<Schedule> findByIdForUpdate(Long scheduleId) {
        return scheduleRepository.findByIdForUpdate(scheduleId);
    }

    @Override
    public List<Schedule> findByPerformanceIdForUpdate(Long performanceId) {
        return scheduleRepository.findByPerformanceIdForUpdate(performanceId);
    }

    @Override
    public boolean existsByPerformanceIdAndStartAt(Long performanceId, LocalDateTime startAt) {
        return scheduleRepository.existsByPerformanceIdAndStartAt(performanceId, startAt);
    }

    @Override
    public PerformancePeriod findActivePeriod(Long performanceId) {
        ScheduleJpaRepository.StartAtRange range =
                scheduleRepository.findStartAtRange(performanceId, OperationStatus.ACTIVE);
        return PerformancePeriod.of(range.getFirstStartAt(), range.getLastStartAt());
    }

    @Override
    public List<Long> findSeatSetupDueIds(LocalDateTime now, int limit) {
        return scheduleRepository.findIdsBySeatSetupDue(SeatSetupStatus.PENDING, now, Limit.of(limit));
    }

    /**
     * Note: Entity를 먼저 영속성 컨텍스트에서 분리한다. 분리하지 않으면 UPDATE 쿼리 전 자동 flush나 커밋 때 변경 감지가 같은 행을
     * 다시 UPDATE하며 version을 올린다. 일괄 UPDATE라 감사 컬럼은 직접 채운다(팀 규칙). 시각은 JPA 감사와 같이 서버 시각이다.
     */
    @Override
    public void saveSeatSetup(Schedule schedule) {
        entityManager.detach(schedule);
        scheduleRepository.updateSeatSetup(
                schedule.getId(),
                schedule.getSeatSetupStatus(),
                schedule.getSeatConfigVersion(),
                schedule.getSeatSetupRequestId(),
                schedule.getSeatSetupError(),
                schedule.getSeatSetupAttempts(),
                schedule.getSeatSetupNextAttemptAt(),
                LocalDateTime.now(),
                auditorAware.getCurrentAuditor().orElse(SYSTEM));
    }
}
