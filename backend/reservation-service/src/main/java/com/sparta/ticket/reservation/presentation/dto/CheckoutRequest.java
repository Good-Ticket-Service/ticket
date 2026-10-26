package com.sparta.ticket.reservation.presentation.dto;

import com.sparta.ticket.reservation.application.dto.CheckoutCommand;

/**
 * 결제 준비 요청. 금액 필드를 두지 않는다. 두 값 모두 선택이다.
 * pgProvider는 payment 설계(docs/payment/plan.md)에 있지만 계약(P1)이 정해지기 전이라 필수로 두지 않는다.
 */
public record CheckoutRequest(Long couponId, String pgProvider) {

    public CheckoutCommand toCommand() {
        return new CheckoutCommand(couponId, pgProvider);
    }
}
