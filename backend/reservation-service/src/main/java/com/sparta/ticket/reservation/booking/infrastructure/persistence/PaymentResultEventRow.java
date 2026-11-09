package com.sparta.ticket.reservation.booking.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 결제 결과 메시지 수신 기록 행. 저장은 중복을 막는 네이티브 INSERT로만 한다. */
@Entity
@Table(name = "p_payment_result_events")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class PaymentResultEventRow {

    @Id
    @Column(name = "event_id", length = 36)
    private String eventId;

    @Column(name = "order_id", nullable = false, length = 64)
    private String orderId;

    @Column(name = "reservation_id", nullable = false)
    private Long reservationId;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;
}
