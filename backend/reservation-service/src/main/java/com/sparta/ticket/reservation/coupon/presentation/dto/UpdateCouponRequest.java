package com.sparta.ticket.reservation.coupon.presentation.dto;

import com.sparta.ticket.reservation.coupon.application.dto.UpdateCouponCommand;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

/** 쿠폰 부분 수정 요청. 바꿀 항목만 보내고, 보내지 않은 항목은 null이다. 발급 시작 후 바꿀 수 있는 항목은 Coupon이 검사한다. */
public record UpdateCouponRequest(
        @Size(min = 1, max = 100) @Pattern(regexp = ".*\\S.*", message = "공백만으로 이름을 정할 수 없습니다.")
        String name,

        @Min(1) Integer discountAmount,
        @Min(1) Integer totalQuantity,
        LocalDateTime issueStartAt,
        LocalDateTime issueEndAt,
        @Min(1) Integer validDays) {

    /** 검증을 통과한 요청을 Service가 받는 Command로 바꾼다. 보내지 않은 항목은 null 그대로 넘긴다. */
    public UpdateCouponCommand toCommand() {
        return new UpdateCouponCommand(name, discountAmount, totalQuantity, issueStartAt, issueEndAt, validDays);
    }
}
