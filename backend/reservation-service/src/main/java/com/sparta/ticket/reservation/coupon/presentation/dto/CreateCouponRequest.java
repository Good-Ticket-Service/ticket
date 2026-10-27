package com.sparta.ticket.reservation.coupon.presentation.dto;

import com.sparta.ticket.reservation.coupon.application.dto.CreateCouponCommand;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

/** 쿠폰 생성 요청. 발급 기간의 순서와 종료 시각이 현재 이후인지는 Coupon이 검사한다. */
public record CreateCouponRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull @Min(1) Integer discountAmount,
        @NotNull @Min(1) Integer totalQuantity,
        @NotNull LocalDateTime issueStartAt,
        @NotNull LocalDateTime issueEndAt,
        @NotNull @Min(1) Integer validDays) {

    public CreateCouponCommand toCommand() {
        return new CreateCouponCommand(name, discountAmount, totalQuantity, issueStartAt, issueEndAt, validDays);
    }
}
