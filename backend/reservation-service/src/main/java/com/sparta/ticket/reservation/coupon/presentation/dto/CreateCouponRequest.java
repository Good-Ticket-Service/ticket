package com.sparta.ticket.reservation.coupon.presentation.dto;

import com.sparta.ticket.reservation.coupon.application.dto.CreateCouponCommand;
import com.sparta.ticket.reservation.coupon.domain.CouponCategory;
import com.sparta.ticket.reservation.coupon.domain.DiscountPolicy;
import com.sparta.ticket.reservation.coupon.domain.DiscountType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

/**
 * 쿠폰 생성 요청. 발급 기간의 순서와 종료 시각이 현재 이후인지는 Coupon이 검사한다.
 * 할인 정책(#348): discountType을 보내지 않으면 정액(FIXED)이다(할인액만 보내던 기존 요청과 같다).
 * 종류별로 필요한 값과 받지 않는 값은 DiscountPolicy.of가 검사한다(정액은 discountAmount, 정률은 discountRate 1~99와 선택 maxDiscountAmount).
 * minBaseAmount(최소 적용 금액)는 두 종류 모두 선택이다.
 * 쿠폰 구분(#357): category를 보내지 않으면 일반(GENERAL)이다. 웰컴(WELCOME)은 validDays를 생략할 수 있고(7) 7만 허용한다.
 * 일반 쿠폰은 validDays가 필요하다. 이 규칙은 Coupon.create가 검사한다.
 */
public record CreateCouponRequest(
        @NotBlank @Size(max = 100) String name,
        CouponCategory category,
        DiscountType discountType,
        @Min(1) Integer discountAmount,
        @Min(1) @Max(DiscountPolicy.MAX_RATE) Integer discountRate,
        @Min(1) Integer maxDiscountAmount,
        @Min(1) Integer minBaseAmount,
        @NotNull @Min(1) Integer totalQuantity,
        @NotNull LocalDateTime issueStartAt,
        @NotNull LocalDateTime issueEndAt,
        @Min(1) Integer validDays) {

    /** 검증을 통과한 요청을 Service가 받는 Command로 바꾼다. 할인 값 조합이 맞지 않으면 여기서 400 VALIDATION_ERROR다. */
    public CreateCouponCommand toCommand() {
        DiscountPolicy policy = DiscountPolicy.of(
                discountType == null ? DiscountType.FIXED : discountType,
                discountAmount,
                discountRate,
                maxDiscountAmount,
                minBaseAmount);
        return new CreateCouponCommand(name, category, policy, totalQuantity, issueStartAt, issueEndAt, validDays);
    }
}
