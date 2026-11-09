package com.sparta.ticket.reservation.booking.application.port;

import java.time.LocalDateTime;

/** payment에 보낼 승인 요청을 남긴다. 결제 중으로 바꾸는 트랜잭션 안에서 불러야 한다. 보내는 일은 발행기가 따로 한다. */
public interface PaymentConfirmRequests {

    void request(PaymentConfirmRequest request);

    /** 승인 요청. confirmBefore가 지나면 payment는 PG를 부르지 않고 실패로 정한다. */
    record PaymentConfirmRequest(
            String orderId, Long reservationId, long amount, String pgAuthToken, LocalDateTime confirmBefore) {}
}
