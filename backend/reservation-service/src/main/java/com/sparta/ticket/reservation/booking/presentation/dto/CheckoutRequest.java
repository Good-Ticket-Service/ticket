package com.sparta.ticket.reservation.booking.presentation.dto;

import com.sparta.ticket.reservation.booking.application.dto.CheckoutCommand;

/**
 * 결제 준비 요청. 금액 필드를 두지 않는다. 두 값 모두 선택이다.
 * pgProvider는 결제 계약에서 필수지만, payment와 연결하기 전이라 아직 선택으로 둔다.
 */
public record CheckoutRequest(Long couponId, String pgProvider) {

    public CheckoutCommand toCommand() {
        return new CheckoutCommand(couponId, pgProvider);
    }
}
