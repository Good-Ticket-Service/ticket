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

    /**
     * HTTP로 대신 보낼 다음 승인 요청의 메시지 본문. 시한이 이른 순으로 하나를 고르고 그 예약 행만 잠근다. 다른 작업자가 잠근
     * 예약은 건너뛰어, 같은 때 고른 두 작업자가 같은 요청을 보내지 않는다. Outbox 행은 잠그지 않으므로 발행기가 그 행을 잠그고
     * 브로커를 기다리는 동안에도 고르고, 발행기도 이 쿼리를 기다리지 않는다.
     * 다른 토픽의 키와 본문은 예약 id나 JSON이 아닐 수 있어, 승인 요청만 먼저 추린 뒤에 바꾼다(MATERIALIZED). 지난 시도의 요청은
     * orderId가 예약의 지금 값과 달라 고르지 않는다.
     * sent는 고르기 전에 보낸 시각이 있었는지다. 결과 확인도 같은 컬럼에 확인 시각을 남기지만 시한과 여유 시간이 지난 행만 집으므로,
     * 시한 전의 행만 고르는 여기서는 그 값을 보지 않는다.
     */
    @Query(value = """
                    WITH requests AS MATERIALIZED (
                        SELECT CAST(message_key AS BIGINT) AS reservation_id,
                               CAST(payload AS jsonb) ->> 'orderId' AS order_id,
                               payload
                          FROM p_booking_outbox
                         WHERE topic = :topic)
                    SELECT q.payload AS payload, r.payment_checked_at IS NOT NULL AS sent
                      FROM requests q
                      JOIN p_reservations r ON r.reservation_id = q.reservation_id AND r.order_id = q.order_id
                     WHERE r.status = 'PAYMENT_IN_PROGRESS'
                       AND r.confirm_before > :now
                       AND r.refund_required_at IS NULL
                       AND COALESCE(r.payment_checked_at, r.updated_at) <= :sentBefore
                     ORDER BY r.confirm_before, r.reservation_id
                     LIMIT 1
                       FOR UPDATE OF r SKIP LOCKED
                    """, nativeQuery = true)
    Optional<StrandedRow> lockNextStranded(
            @Param("topic") String topic,
            @Param("now") LocalDateTime now,
            @Param("sentBefore") LocalDateTime sentBefore);

    interface StrandedRow {

        String getPayload();

        boolean isSent();
    }
}
