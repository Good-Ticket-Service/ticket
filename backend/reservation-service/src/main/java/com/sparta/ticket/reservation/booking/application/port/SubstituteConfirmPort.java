package com.sparta.ticket.reservation.booking.application.port;

import com.sparta.ticket.reservation.booking.application.port.PaymentConfirmRequests.PaymentConfirmRequest;

/**
 * 메시지로 남긴 승인 요청을 payment에 HTTP로 대신 보낸다(#403). payment는 confirmBefore가 실린 승인을 메시지 승인과 같은
 * 규칙(시한, PG별 자리)으로 받고, PG를 기다리지 않고 바로 답한다(#422).
 *
 * <p>요청은 Outbox 행이 가진 값 그대로다. 한 번의 대기는 DrainSettings.resendAfter보다 짧아야 한다. 길면 답을 기다리는 동안
 * 다른 작업자가 같은 요청을 다시 집는다. 예외를 던지면 호출자는 답을 받지 못한 것으로 본다.
 */
public interface SubstituteConfirmPort {

    SubstituteAnswer confirm(PaymentConfirmRequest request);

    /**
     * 이미 보낸 승인의 결과를 결제 조회로 묻는다. 결과를 모르는 승인을 다시 보내지 않는 것이 #422 계약이라, 보낸 뒤의 확인은
     * 이것으로만 한다. 브로커가 멈춰 있어 결과 메시지를 기다릴 수 없다.
     */
    SubstituteAnswer lookup(PaymentConfirmRequest request);
}
