package com.sparta.ticket.reservation.booking.application.port;

import java.time.LocalDateTime;
import java.util.Optional;

/** payment에 보낼 승인 요청을 남긴다. 결제 중으로 바꾸는 트랜잭션 안에서 불러야 한다. 보내는 일은 발행기가 따로 한다. */
public interface PaymentConfirmRequests {

    void request(PaymentConfirmRequest request);

    /**
     * 남겼지만 브로커가 받지 않은 메시지 중 발행기가 다음에 보낼 것을 남긴 시각. 없으면 비어 있다.
     * 발행기는 남긴 순서대로 보내고 하나라도 못 보내면 그 차례를 멈추므로, 지금 남기는 승인 요청은 이 메시지가 나간 뒤에 나간다.
     * 그래서 승인 요청만 고르지 않는다. 이미 열린 트랜잭션 안에서만 부른다.
     */
    Optional<LocalDateTime> oldestWaitingSince();

    /** 승인 요청. confirmBefore가 지나면 payment는 PG를 부르지 않고 실패로 정한다. */
    record PaymentConfirmRequest(
            String orderId, Long reservationId, long amount, String pgAuthToken, LocalDateTime confirmBefore) {}
}
