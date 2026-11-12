package com.sparta.ticket.reservation.booking.infrastructure.messaging;

/** Outbox에 새 행이 커밋됐다고 발행 쪽에 알린다. 사용자 요청 스레드가 부르므로 브로커를 기다리지 않고 바로 돌아와야 한다. */
public interface OutboxWakeup {

    void wake();
}
