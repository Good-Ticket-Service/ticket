package com.sparta.ticket.reservation.seat.infrastructure.persistence;

import com.sparta.ticket.reservation.seat.domain.GradeRemaining;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeat;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeatRepository;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface ScheduleSeatJpaRepository extends JpaRepository<ScheduleSeat, Long>, ScheduleSeatRepository {

    /**
     * Note: 여러 좌석을 잠그는 요청이 서로 다른 순서로 잠그면 교착 상태가 된다. 모든 경로가 좌석 id 오름차순으로 잠근다.
     * booking의 확정·만료도 좌석을 같은 순서로 잠근 뒤 예약을 바꾼다.
     */
    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ScheduleSeat s WHERE s.id IN :ids ORDER BY s.id")
    List<ScheduleSeat> findAllByIdsForUpdate(@Param("ids") Collection<Long> ids);

    /**
     * Note: 잠금 대기 시간 0을 주면 Hibernate가 FOR UPDATE 뒤에 NOWAIT를 붙인다. 잠금 순서는 위 메서드와 같은 id 오름차순이다.
     * 요청 좌석만 NOWAIT로 잠그고 나머지 좌석을 따로 기다려 잠그면 잠금 순서가 섞여 교착할 수 있어, 함께 잠글 좌석을 이 한 쿼리로
     * 모두 잠근다.
     */
    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query("SELECT s FROM ScheduleSeat s WHERE s.id IN :ids ORDER BY s.id")
    List<ScheduleSeat> findAllByIdsForUpdateNoWait(@Param("ids") Collection<Long> ids);

    /** 회차별 조회는 ix_p_schedule_seats_selectable(schedule_id, is_sellable, status)의 앞 컬럼을 탄다. */
    @Override
    @Query("SELECT s FROM ScheduleSeat s WHERE s.scheduleId = :scheduleId ORDER BY s.id")
    List<ScheduleSeat> findAllBySchedule(@Param("scheduleId") Long scheduleId);

    /**
     * 판매 대상 좌석만 등급별로 묶어 그중 AVAILABLE을 센다. 판매 대상 조건으로 거르므로 판매 대상이 없는 등급은 결과에 없고,
     * 판매 대상이 있는데 모두 선점·판매됐으면 0이다. ix_p_schedule_seats_selectable(schedule_id, is_sellable, status)를 탄다.
     * Note: 만료됐지만 아직 정리되지 않은 HOLD는 세지 않는다. 좌석 배치도와 달리 결제 중인지 booking에 묻지 않고, 만료 스케줄러가
     * 곧 AVAILABLE로 돌려놓는다. 차이는 좌석이 적게 보이는 쪽으로만 난다(#241).
     */
    @Override
    @Query("""
            SELECT new com.sparta.ticket.reservation.seat.domain.GradeRemaining(
                       s.venueSeatGradeId, s.gradeName,
                       SUM(CASE WHEN s.status = com.sparta.ticket.reservation.seat.domain.SeatStatus.AVAILABLE THEN 1L ELSE 0L END))
              FROM ScheduleSeat s
             WHERE s.scheduleId = :scheduleId AND s.sellable = true
             GROUP BY s.venueSeatGradeId, s.gradeName
             ORDER BY s.venueSeatGradeId
            """)
    List<GradeRemaining> countRemainingByGrade(@Param("scheduleId") Long scheduleId);

    /**
     * Note: reservation_id 조건이 있어 이미 다른 예약에 넘어간 좌석은 바뀌지 않는다. 늦게 도착한 결제 결과가 새 선점을 SOLD로 만들지 못한다.
     * 조건부 UPDATE는 JPA 감사를 거치지 않으므로 updated_at·updated_by를 쿼리가 채운다. 아직 쓰지 않은 변경이 UPDATE보다 늦게
     * 나가지 않게 flushAutomatically를 켠다. 같은 트랜잭션에서 먼저 읽은 좌석 Entity는 바뀌기 전 값이므로 다시 판단에 쓰지 않는다.
     * 일괄 UPDATE에는 Entity의 @SQLRestriction이 붙는다고 장담할 수 없어 논리 삭제 조건을 직접 적는다.
     */
    @Override
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE ScheduleSeat s
               SET s.status = com.sparta.ticket.reservation.seat.domain.SeatStatus.SOLD,
                   s.holdExpiresAt = NULL, s.updatedAt = :at, s.updatedBy = :by
             WHERE s.id IN :ids AND s.reservationId = :reservationId AND s.deletedAt IS NULL
               AND s.status = com.sparta.ticket.reservation.seat.domain.SeatStatus.HOLD
            """)
    int sell(
            @Param("ids") Collection<Long> ids,
            @Param("reservationId") Long reservationId,
            @Param("at") LocalDateTime at,
            @Param("by") String by);

    /** 만료·취소 모두 쓰므로 HOLD·SOLD를 가리지 않는다. AVAILABLE은 reservation_id가 없어 조건에 걸리지 않는다. */
    @Override
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE ScheduleSeat s
               SET s.status = com.sparta.ticket.reservation.seat.domain.SeatStatus.AVAILABLE,
                   s.reservationId = NULL, s.holdExpiresAt = NULL, s.updatedAt = :at, s.updatedBy = :by
             WHERE s.id IN :ids AND s.reservationId = :reservationId AND s.deletedAt IS NULL
            """)
    int release(
            @Param("ids") Collection<Long> ids,
            @Param("reservationId") Long reservationId,
            @Param("at") LocalDateTime at,
            @Param("by") String by);
}
