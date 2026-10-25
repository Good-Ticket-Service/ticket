package com.sparta.ticket.payment.domain.exception.payment;

import com.sparta.ticket.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.springframework.http.HttpStatus;

@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum PaymentErrorCode implements ErrorCode {
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "결제를 찾을 수 없습니다."),
    PAYMENT_ORDER_CONFLICT(HttpStatus.CONFLICT, "같은 주문번호로 다른 결제를 요청할 수 없습니다."),
    PAYMENT_ALREADY_IN_PROGRESS(HttpStatus.CONFLICT, "이 예매의 결제가 이미 진행 중입니다."),
    PAYMENT_ALREADY_DONE(HttpStatus.CONFLICT, "이 예매는 이미 결제됐습니다."),
    PAYMENT_CHECKOUT_CONFLICT(HttpStatus.CONFLICT, "같은 예매의 결제 준비가 동시에 요청됐습니다."),
    PAYMENT_AMOUNT_MISMATCH(HttpStatus.BAD_REQUEST, "결제 금액이 준비된 금액과 다릅니다."),
    INVALID_PAYMENT_AMOUNT(HttpStatus.BAD_REQUEST, "결제 금액이 올바르지 않습니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String code() {
        return name();
    }
}
