package com.sparta.ticket.reservation.booking.presentation.dto;

import com.sparta.ticket.reservation.booking.application.dto.CheckoutCommand;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** 결제 준비 요청. 금액 필드를 두지 않는다. 쿠폰은 선택이고 PG는 필수다. */
public record CheckoutRequest(
        Long userCouponId,
        @NotNull @Pattern(regexp = "TOSS|KAKAO") String pgProvider) {

    public CheckoutCommand toCommand() {
        return new CheckoutCommand(userCouponId, pgProvider);
    }
}
