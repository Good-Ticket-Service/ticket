package com.sparta.ticket.reservation.booking.domain;

import java.time.LocalDateTime;

/** payment가 보낸 결제 결과 메시지의 수신 기록. 같은 메시지가 다시 와도 한 번만 반영하기 위한 키가 eventId다. */
public interface PaymentResultEventRepository {

    /** 받은 메시지를 기록한다. 같은 eventId가 이미 있으면 처음 기록을 그대로 두고 0행이다. */
    int recordIfAbsent(
            String eventId, String orderId, Long reservationId, LocalDateTime occurredAt, LocalDateTime receivedAt);
}
