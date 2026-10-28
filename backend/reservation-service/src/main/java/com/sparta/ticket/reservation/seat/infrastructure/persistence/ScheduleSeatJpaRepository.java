package com.sparta.ticket.reservation.seat.infrastructure.persistence;

import com.sparta.ticket.reservation.seat.domain.ScheduleSeat;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeatRepository;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
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
