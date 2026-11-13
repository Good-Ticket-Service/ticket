package com.sparta.ticket.reservation.booking.infrastructure.payment;

import com.sparta.ticket.reservation.booking.application.port.PaymentConfirmRequests.PaymentConfirmRequest;
import com.sparta.ticket.reservation.infrastructure.config.ClockConfig;
import java.time.OffsetDateTime;

/**
 * confirmBefore를 실은 POST /internal/payments/{orderId}/confirm 요청(#422). payment는 같은 주문의 Outbox 메시지와 이 값들로 만든
 * 지문을 비교하므로 메시지와 같은 시간대로 바꾸고 자르지 않는다. 인증값은 로그에 남지 않게 toString에서 가린다.
 */
public record SubstituteConfirmRequest(String pgAuthToken, int amount, OffsetDateTime confirmBefore) {

    static SubstituteConfirmRequest from(PaymentConfirmRequest request) {
        return new SubstituteConfirmRequest(
                request.pgAuthToken(),
                Math.toIntExact(request.amount()),
                request.confirmBefore().atZone(ClockConfig.ZONE).toOffsetDateTime());
    }

    @Override
    public String toString() {
        return "SubstituteConfirmRequest[pgAuthToken=REDACTED, amount=" + amount + ", confirmBefore=" + confirmBefore
                + "]";
    }
}
