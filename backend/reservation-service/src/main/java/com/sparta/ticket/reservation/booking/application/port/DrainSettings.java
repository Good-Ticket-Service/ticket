package com.sparta.ticket.reservation.booking.application.port;

import java.time.Duration;

/** Outbox에 묶인 승인 요청을 HTTP로 대신 보내는 작업자의 설정. */
public interface DrainSettings {

    /** 끄면 묶인 요청은 브로커가 돌아오거나 결과 확인이 시한 뒤 끝낼 때까지 기다린다. */
    boolean enabled();

    /** 인스턴스 하나가 동시에 payment로 보내는 요청 수. 작업자 스레드 수다. */
    int concurrency();

    /** 보낸 요청을 다시 보낼 수 있게 되기까지의 시간. payment가 받았다고만 답한 요청은 이 간격으로 다시 보내 결과를 묻는다. */
    Duration resendAfter();

    /** payment가 돌려보낼 때 쉬는 시간의 상한. 더 오래 쉬라고 해도 이만큼만 쉬고 다시 보낸다. */
    Duration maxBackoff();
}
