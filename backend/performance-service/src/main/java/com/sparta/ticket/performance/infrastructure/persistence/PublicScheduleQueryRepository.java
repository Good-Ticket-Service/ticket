package com.sparta.ticket.performance.infrastructure.persistence;

import com.sparta.ticket.performance.application.dto.PublicScheduleDetailRow;
import com.sparta.ticket.performance.application.dto.PublicScheduleRow;
import com.sparta.ticket.performance.application.port.PublicScheduleReader;
import com.sparta.ticket.performance.domain.performance.OperationStatus;
import com.sparta.ticket.performance.domain.performance.PublicationStatus;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * PublicScheduleReader 구현. 회차 목록과 단건 모두 고정 JPQL 저장소로 읽는다.
 * 목록은 공개 공연 확인(공연·공연장 조인)과 회차 조회를 나눠 읽고(이슈 #400), 단건은 회차와 공개 공연 확인을 한 번에 읽는다(이슈 #387).
 * Note: 회차 목록은 부분 UNIQUE 인덱스 uq_p_schedules_start(performance_id, start_at)가 조건과 정렬을 받친다.
 * 그 밖의 조회용 인덱스는 아직 없다. 성능 기준선을 측정한 뒤 단계별로 추가한다.
 */
@Repository
@RequiredArgsConstructor
public class PublicScheduleQueryRepository implements PublicScheduleReader {

    private final PublicScheduleJpaRepository scheduleJpaRepository;

    @Override
    public Optional<OperationStatus> findPublicPerformanceStatus(Long performanceId) {
        return scheduleJpaRepository.findPublicPerformanceStatus(performanceId, PublicationStatus.PUBLISHED);
    }

    @Override
    public List<PublicScheduleRow> findSchedules(Long performanceId) {
        return scheduleJpaRepository.findSchedules(performanceId);
    }

    @Override
    public Optional<PublicScheduleDetailRow> findPublicSchedule(Long scheduleId) {
        return scheduleJpaRepository
                .findPublicSchedule(scheduleId, PublicationStatus.PUBLISHED)
                .map(row -> new PublicScheduleDetailRow(
                        new PublicScheduleRow(
                                row.id(),
                                row.performanceId(),
                                row.startAt(),
                                row.bookingOpenAt(),
                                row.bookingCloseAt(),
                                row.operationStatus(),
                                row.seatSetupStatus(),
                                row.salesSuspended(),
                                row.canceledAt()),
                        row.performanceStatus()));
    }
}
