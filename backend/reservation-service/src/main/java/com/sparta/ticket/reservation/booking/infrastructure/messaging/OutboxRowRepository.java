package com.sparta.ticket.reservation.booking.infrastructure.messaging;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface OutboxRowRepository extends JpaRepository<OutboxRow, Long> {

    /**
     * 가장 오래된 행부터 잠근다. 다른 발행기가 잠근 행은 건너뛰어, 인스턴스가 여럿이어도 한 행은 한 곳에서만 보낸다.
     * 잠금은 지울 때까지 트랜잭션 끝까지 유지된다.
     */
    @Query(
            value = "SELECT * FROM p_booking_outbox ORDER BY outbox_id LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<OutboxRow> lockOldest(@Param("limit") int limit);
}
