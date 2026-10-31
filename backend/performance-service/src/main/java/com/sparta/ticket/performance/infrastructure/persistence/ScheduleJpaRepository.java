package com.sparta.ticket.performance.infrastructure.persistence;

import com.sparta.ticket.performance.domain.performance.OperationStatus;
import com.sparta.ticket.performance.domain.schedule.Schedule;
import com.sparta.ticket.performance.domain.schedule.SeatSetupStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 회차의 Spring Data 저장소. 삭제된 회차는 Entity의 @SQLRestriction으로 빠진다. */
public interface ScheduleJpaRepository extends JpaRepository<Schedule, Long> {

    /** 공연의 회차 행을 ID 순서로 FOR UPDATE로 잠그고 읽는다. 순서를 정해 여러 행을 잠글 때의 교착을 피한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Schedule s WHERE s.performanceId = :performanceId ORDER BY s.id")
    List<Schedule> findByPerformanceIdForUpdate(@Param("performanceId") Long performanceId);

    /** 회차 행을 FOR UPDATE로 잠그고 읽는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Schedule s WHERE s.id = :id")
    Optional<Schedule> findByIdForUpdate(@Param("id") Long id);

    boolean existsByPerformanceIdAndStartAt(Long performanceId, LocalDateTime startAt);

    /** 공연의 운영 상태가 status인 회차의 첫·마지막 시작 시각. 회차가 없으면 두 값이 null이다. */
    @Query("SELECT MIN(s.startAt) AS firstStartAt, MAX(s.startAt) AS lastStartAt FROM Schedule s"
            + " WHERE s.performanceId = :performanceId AND s.operationStatus = :status")
    StartAtRange findStartAtRange(@Param("performanceId") Long performanceId, @Param("status") OperationStatus status);

    interface StartAtRange {
        LocalDateTime getFirstStartAt();

        LocalDateTime getLastStartAt();
    }

    @Query("SELECT s.id FROM Schedule s WHERE s.seatSetupStatus = :status AND s.seatSetupNextAttemptAt <= :now"
            + " ORDER BY s.seatSetupNextAttemptAt, s.id")
    List<Long> findIdsBySeatSetupDue(
            @Param("status") SeatSetupStatus status, @Param("now") LocalDateTime now, Limit limit);

    /** 좌석 준비 필드와 감사 컬럼만 바꾼다. JPQL 일괄 UPDATE는 @Version을 올리지 않는다. */
    @Modifying
    @Query("UPDATE Schedule s SET s.seatSetupStatus = :status, s.seatConfigVersion = :configVersion,"
            + " s.seatSetupRequestId = :requestId, s.seatSetupError = :error, s.seatSetupAttempts = :attempts,"
            + " s.seatSetupNextAttemptAt = :nextAttemptAt, s.updatedAt = :updatedAt, s.updatedBy = :updatedBy"
            + " WHERE s.id = :id")
    int updateSeatSetup(
            @Param("id") Long id,
            @Param("status") SeatSetupStatus status,
            @Param("configVersion") long configVersion,
            @Param("requestId") String requestId,
            @Param("error") String error,
            @Param("attempts") int attempts,
            @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
            @Param("updatedAt") LocalDateTime updatedAt,
            @Param("updatedBy") String updatedBy);
}
