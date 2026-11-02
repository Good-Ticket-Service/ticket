package com.sparta.ticket.reservation.booking.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 취소 기록 행. 저장은 중복을 막는 네이티브 INSERT로만 한다. */
@Entity
@Table(name = "p_schedule_cancellations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class ScheduleCancellationRow {

    @Id
    @Column(name = "schedule_id")
    private Long scheduleId;

    @Column(name = "event_id", nullable = false, length = 36)
    private String eventId;

    @Column(name = "canceled_at", nullable = false)
    private LocalDateTime canceledAt;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}
