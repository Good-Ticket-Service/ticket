package com.sparta.ticket.reservation.booking.infrastructure.messaging;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
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

    /**
     * 발행기가 다음에 보낼 행을 남긴 시각. lockOldest와 같은 순서로 골라야 하므로 발행 순서를 바꾸면 함께 바꾼다.
     * 잠그지 않아 발행기가 잠근 행도 기다리지 않고 읽는다.
     */
    @Query("SELECT o.createdAt FROM OutboxRow o ORDER BY o.id LIMIT 1")
    Optional<LocalDateTime> findOldestCreatedAt();
}
