package com.sparta.ticket.reservation.coupon.presentation.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sparta.ticket.reservation.coupon.application.dto.UpdateCouponCommand;
import com.sparta.ticket.reservation.coupon.domain.DiscountPolicy;
import com.sparta.ticket.reservation.coupon.domain.DiscountType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.stream.Stream;

/**
 * 쿠폰 부분 수정 요청. 바꿀 항목만 보내고, 보내지 않은 항목은 null이다. 발급 시작 후 바꿀 수 있는 항목은 Coupon이 검사한다.
 * 할인 정책(#348)은 묶음 전체를 바꾼다. 할인 값을 하나라도 보내면 discountType과 그 종류에 필요한 값을 함께 보내야 한다.
 * 함께 보내지 않은 할인 값(예: minBaseAmount)은 "없음"으로 바뀐다. 일부만 바꾸면 정액에 할인율이 붙는 식으로 어긋나기 때문이다.
 */
public record UpdateCouponRequest(
        @Size(min = 1, max = 100) @Pattern(regexp = ".*\\S.*", message = "공백만으로 이름을 정할 수 없습니다.")
        String name,

        DiscountType discountType,
        @Min(1) Integer discountAmount,
        @Min(1) @Max(DiscountPolicy.MAX_RATE) Integer discountRate,
        @Min(1) Integer maxDiscountAmount,
        @Min(1) Integer minBaseAmount,
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
        return isDiscountPresent()
                || Stream.of(name, totalQuantity, issueStartAt, issueEndAt, validDays)
                        .anyMatch(value -> value != null);
    }

    /** 할인 값을 보냈으면 종류도 보내야 한다. 종류 없이 할인율만 오면 어느 정책으로 바꿀지 알 수 없다. */
    @JsonIgnore
    @AssertTrue(message = "할인 정책을 바꾸려면 discountType을 함께 보내야 합니다.")
    public boolean isDiscountTypePresentWhenDiscountChanges() {
        return !isDiscountPresent() || discountType != null;
    }

    /** 검증을 통과한 요청을 Service가 받는 Command로 바꾼다. 보내지 않은 항목은 null 그대로 넘긴다. */
    public UpdateCouponCommand toCommand() {
        // 할인 값을 보냈을 때만 새 정책을 만든다. 조합이 맞지 않으면 여기서 400 VALIDATION_ERROR다
        DiscountPolicy policy = isDiscountPresent()
                ? DiscountPolicy.of(discountType, discountAmount, discountRate, maxDiscountAmount, minBaseAmount)
                : null;
        return new UpdateCouponCommand(name, policy, totalQuantity, issueStartAt, issueEndAt, validDays);
    }

    private boolean isDiscountPresent() {
        return Stream.of(discountType, discountAmount, discountRate, maxDiscountAmount, minBaseAmount)
                .anyMatch(value -> value != null);
    }
}
