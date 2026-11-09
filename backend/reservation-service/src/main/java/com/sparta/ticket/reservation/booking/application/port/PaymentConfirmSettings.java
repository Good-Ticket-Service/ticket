package com.sparta.ticket.reservation.booking.application.port;

import java.time.Duration;

/** 결제 승인을 동기로 부를지 메시지로 요청할지와, 메시지로 요청한 승인의 시한. */
public interface PaymentConfirmSettings {

    /** 켜면 승인 API가 payment를 기다리지 않고 승인 요청을 메시지로 남긴 뒤 바로 답한다. 결제 소비자가 없으면 끈다. */
    boolean viaMessage();

    /** 승인 요청을 받은 시각에 더해 시한을 정한다. payment는 시한이 지난 요청에 PG를 부르지 않는다. */
    Duration window();

    /**
     * 결과 확인이 시한 뒤 더 기다리는 시간. 시한은 예매 시계로 정하고 payment 시계로 비교하므로 두 시계의 차이보다 커야 한다.
     * 짧으면 예매가 실패로 끝낸 뒤 시계가 늦은 payment가 아직 시한 안이라며 PG를 부를 수 있다.
     */
    Duration grace();
}
