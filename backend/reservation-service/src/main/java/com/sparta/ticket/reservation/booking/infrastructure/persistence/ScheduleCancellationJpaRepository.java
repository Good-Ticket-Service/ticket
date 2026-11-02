package com.sparta.ticket.reservation.booking.infrastructure.persistence;

import com.sparta.ticket.reservation.booking.domain.ScheduleCancellationRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ScheduleCancellationJpaRepository
        extends JpaRepository<ScheduleCancellationRow, Long>, ScheduleCancellationRepository {

    @Override
    @Modifying
    @Query(value = """
                    INSERT INTO p_schedule_cancellations (schedule_id, event_id, canceled_at, received_at, status)
                    VALUES (:scheduleId, :eventId, :canceledAt, :receivedAt, 'RECEIVED')
                    ON CONFLICT (schedule_id) DO NOTHING
                    """, nativeQuery = true)
    int recordIfAbsent(
            @Param("scheduleId") Long scheduleId,
            @Param("eventId") String eventId,
            @Param("canceledAt") LocalDateTime canceledAt,
            @Param("receivedAt") LocalDateTime receivedAt);

    @Override
    default List<Long> findUnfinishedScheduleIds(int limit) {
        return selectUnfinishedScheduleIds(PageRequest.of(0, limit));
    }

    @Query("""
            SELECT c.scheduleId FROM ScheduleCancellationRow c
             WHERE c.status = 'RECEIVED'
             ORDER BY c.receivedAt, c.scheduleId
            """)
    List<Long> selectUnfinishedScheduleIds(Pageable pageable);

    @Override
    @Modifying
    @Query("""
            UPDATE ScheduleCancellationRow c SET c.status = 'COMPLETED', c.completedAt = :completedAt
             WHERE c.scheduleId = :scheduleId AND c.status = 'RECEIVED' AND c.receivedAt <= :receivedBefore
            """)
    int complete(
            @Param("scheduleId") Long scheduleId,
            @Param("receivedBefore") LocalDateTime receivedBefore,
            @Param("completedAt") LocalDateTime completedAt);
}
