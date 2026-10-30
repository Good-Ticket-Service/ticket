package com.sparta.ticket.reservation.coupon.presentation.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sparta.ticket.reservation.coupon.application.dto.UpdateCouponCommand;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.stream.Stream;

/** 쿠폰 부분 수정 요청. 바꿀 항목만 보내고, 보내지 않은 항목은 null이다. 발급 시작 후 바꿀 수 있는 항목은 Coupon이 검사한다. */
public record UpdateCouponRequest(
        @Size(min = 1, max = 100) @Pattern(regexp = ".*\\S.*", message = "공백만으로 이름을 정할 수 없습니다.")
        String name,

        @Min(1) Integer discountAmount,
        @Min(1) Integer totalQuantity,
        LocalDateTime issueStartAt,
        LocalDateTime issueEndAt,
        @Min(1) Integer validDays) {

    /**
     * 바꿀 항목이 하나 이상 있어야 한다. 빈 본문 {}나 모두 null이면 400 VALIDATION_ERROR다.
     * 필드 이름을 잘못 보내면(예: title) 모르는 필드는 무시돼 빈 요청이 되는데, 이를 성공으로 응답하면 바뀐 줄 알게 된다.
     */
    @JsonIgnore
    @AssertTrue(message = "바꿀 항목을 하나 이상 보내야 합니다.")
    public boolean isAnyFieldPresent() {
        return Stream.of(name, discountAmount, totalQuantity, issueStartAt, issueEndAt, validDays)
                .anyMatch(value -> value != null);
    }

    /** 검증을 통과한 요청을 Service가 받는 Command로 바꾼다. 보내지 않은 항목은 null 그대로 넘긴다. */
    public UpdateCouponCommand toCommand() {
        return new UpdateCouponCommand(name, discountAmount, totalQuantity, issueStartAt, issueEndAt, validDays);
    }
}
