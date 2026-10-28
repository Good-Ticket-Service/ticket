package com.sparta.ticket.reservation.booking.infrastructure.payment;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.reservation.booking.application.port.ConfirmOutcome;
import com.sparta.ticket.reservation.booking.application.port.PaymentLookup;
import com.sparta.ticket.reservation.booking.application.port.PaymentPort;
import com.sparta.ticket.reservation.booking.application.port.PaymentPrepareRequest;
import com.sparta.ticket.reservation.booking.application.port.PreparedPayment;
import org.springframework.stereotype.Component;

/**
 * payment Feign 어댑터가 생기기 전의 자리. payment 동작을 흉내 내지 않고 503으로 답한다.
 * 결제 준비에서 막히므로 결제 중 예약이 생기지 않는다. Feign 어댑터를 만들면 이 클래스를 지운다.
 */
@Component
public class UnconnectedPaymentAdapter implements PaymentPort {

    @Override
    public PreparedPayment prepare(PaymentPrepareRequest request) {
        throw notConnected();
    }

    @Override
    public ConfirmOutcome confirm(String orderId, String pgAuthToken, long amount) {
        throw notConnected();
    }

    @Override
    public PaymentLookup find(String orderId) {
        throw notConnected();
    }

    private static BusinessException notConnected() {
        return new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, "결제 연동이 아직 준비되지 않았습니다.");
    }
}
