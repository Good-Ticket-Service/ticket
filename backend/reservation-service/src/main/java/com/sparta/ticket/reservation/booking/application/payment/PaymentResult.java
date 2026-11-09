package com.sparta.ticket.reservation.booking.application.payment;

import com.sparta.ticket.reservation.booking.application.port.ConfirmOutcome;
import java.time.LocalDateTime;

/** payment가 정한 한 결제 시도의 결과. 메시지 형식과 무관한 booking의 말이다. */
public record PaymentResult(
        String eventId, Long reservationId, String orderId, ConfirmOutcome outcome, LocalDateTime occurredAt) {}
