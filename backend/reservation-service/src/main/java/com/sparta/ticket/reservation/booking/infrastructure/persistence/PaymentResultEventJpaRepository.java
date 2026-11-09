package com.sparta.ticket.reservation.booking.infrastructure.persistence;

import com.sparta.ticket.reservation.booking.domain.PaymentResultEventRepository;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PaymentResultEventJpaRepository
        extends JpaRepository<PaymentResultEventRow, String>, PaymentResultEventRepository {

    @Override
    @Modifying
    @Query(value = """
                    INSERT INTO p_payment_result_events (event_id, order_id, reservation_id, occurred_at, received_at)
                    VALUES (:eventId, :orderId, :reservationId, :occurredAt, :receivedAt)
                    ON CONFLICT (event_id) DO NOTHING
                    """, nativeQuery = true)
    int recordIfAbsent(
            @Param("eventId") String eventId,
            @Param("orderId") String orderId,
            @Param("reservationId") Long reservationId,
            @Param("occurredAt") LocalDateTime occurredAt,
            @Param("receivedAt") LocalDateTime receivedAt);
}
