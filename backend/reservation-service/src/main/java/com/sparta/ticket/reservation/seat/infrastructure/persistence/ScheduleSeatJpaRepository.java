package com.sparta.ticket.reservation.seat.infrastructure.persistence;

import com.sparta.ticket.reservation.seat.domain.ScheduleSeat;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeatRepository;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ScheduleSeatJpaRepository extends JpaRepository<ScheduleSeat, Long>, ScheduleSeatRepository {

    /**
     * Note: 여러 좌석을 잠그는 요청이 서로 다른 순서로 잠그면 교착 상태가 된다. 모든 경로가 좌석 id 오름차순으로 잠근다.
     * booking의 확정·만료도 좌석을 같은 순서로 잠근 뒤 예약을 바꾼다(docs/architecture.md 11절).
     */
    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ScheduleSeat s WHERE s.id IN :ids ORDER BY s.id")
    List<ScheduleSeat> findAllByIdsForUpdate(@Param("ids") Collection<Long> ids);
}
