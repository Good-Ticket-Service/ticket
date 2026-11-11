package com.sparta.ticket.reservation.coupon.domain;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 쿠폰 할인 정책(#348). 쿠폰 정의(p_coupons)와 회원 쿠폰(p_user_coupons, 발급 시점 복사본)이 같은 묶음을 갖는다.
 * 할인액 계산과 최소 적용 금액 판단을 여기 한 곳에 두어, 생성 검증·발급 복사·결제 준비 계산이 같은 규칙을 쓴다.
 * - FIXED(정액): discountAmount만 있다
 * - RATE(정률): discountRate(1~99%)가 있고, maxDiscountAmount(최대 할인액)는 선택이다
 * - 공통: minBaseAmount(최소 적용 금액)는 선택이다. 없으면 정가와 상관없이 쓸 수 있다
 * 값이 같으면 같은 정책이다(수정 때 "바뀌었는지"를 equals로 판단한다).
 */
@Getter
@Embeddable
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DiscountPolicy {

    /** 결제 최소 금액(100원) 때문에 100%는 표시대로 무료가 되지 않아 받지 않는다. */
    public static final int MAX_RATE = 99;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DiscountType discountType;

    /** 정액 할인액(원). FIXED일 때만 있다. */
    private Integer discountAmount;

    /** 할인율(%). RATE일 때만 있다. */
    private Integer discountRate;

    /** 정률 최대 할인액(원). RATE일 때만 둘 수 있고, 없으면 제한 없다. */
    private Integer maxDiscountAmount;

    /** 최소 적용 금액(원). 정가가 이보다 작으면 쓸 수 없다. 없으면 제한 없다. */
    private Integer minBaseAmount;

    private DiscountPolicy(
            DiscountType discountType,
            Integer discountAmount,
            Integer discountRate,
            Integer maxDiscountAmount,
            Integer minBaseAmount) {
        this.discountType = discountType;
        this.discountAmount = discountAmount;
        this.discountRate = discountRate;
        this.maxDiscountAmount = maxDiscountAmount;
        this.minBaseAmount = minBaseAmount;
    }

    /**
     * 요청 값으로 정책을 만든다. 종류에 맞지 않는 값이 오거나 범위를 벗어나면 400 VALIDATION_ERROR다.
     * DB의 종류별 CHECK 제약(ck_p_coupons_discount 등)과 같은 규칙이다.
     */
    public static DiscountPolicy of(
            DiscountType discountType,
            Integer discountAmount,
            Integer discountRate,
            Integer maxDiscountAmount,
            Integer minBaseAmount) {
        if (discountType == null) {
            throw invalid("할인 종류를 정해야 합니다.");
        }
        // 1) 종류별 필수 값과 받지 않는 값
        if (discountType == DiscountType.FIXED) {
            requirePositive(discountAmount, "정액 쿠폰은 할인액이 1 이상이어야 합니다.");
            if (discountRate != null || maxDiscountAmount != null) {
                throw invalid("정액 쿠폰에는 할인율과 최대 할인액을 둘 수 없습니다.");
            }
        } else {
            if (discountRate == null || discountRate < 1 || discountRate > MAX_RATE) {
                throw invalid("정률 쿠폰은 할인율이 1~" + MAX_RATE + "%여야 합니다.");
            }
            if (discountAmount != null) {
                throw invalid("정률 쿠폰에는 할인액을 둘 수 없습니다. 최대 할인액을 쓰세요.");
            }
            if (maxDiscountAmount != null) {
                requirePositive(maxDiscountAmount, "최대 할인액은 1 이상이어야 합니다.");
            }
        }
        // 2) 최소 적용 금액은 두 종류 모두 선택이다
        if (minBaseAmount != null) {
            requirePositive(minBaseAmount, "최소 적용 금액은 1 이상이어야 합니다.");
        }
        return new DiscountPolicy(discountType, discountAmount, discountRate, maxDiscountAmount, minBaseAmount);
    }

    /** 최소 적용 금액 없는 정액 정책. */
    public static DiscountPolicy fixed(int discountAmount) {
        return of(DiscountType.FIXED, discountAmount, null, null, null);
    }

    /**
     * 같은 값의 새 인스턴스. 회원 쿠폰에 복사할 때 쓴다. 임베디드 값은 Entity마다 따로 두어야 한쪽 변경이 다른 쪽에 섞이지 않는다.
     */
    public DiscountPolicy copy() {
        return new DiscountPolicy(discountType, discountAmount, discountRate, maxDiscountAmount, minBaseAmount);
    }

    /** 정가가 최소 적용 금액 이상인지. 최소 적용 금액이 없으면 항상 쓸 수 있다. */
    public boolean isApplicableTo(long baseAmount) {
        return minBaseAmount == null || baseAmount >= minBaseAmount;
    }

    /**
     * 정가가 최소 적용 금액보다 작으면 COUPON_NOT_AVAILABLE이다. 새 오류 코드 없이, 메시지에 금액을 넣어
     * 화면이 "30,000원 이상 결제 시 사용 가능"처럼 이유를 보여 줄 수 있게 한다(#347 합의).
     */
    public void requireApplicableTo(long baseAmount) {
        if (!isApplicableTo(baseAmount)) {
            throw new BusinessException(
                    CouponErrorCode.COUPON_NOT_AVAILABLE, String.format("%,d원 이상 결제 시 사용할 수 있는 쿠폰입니다.", minBaseAmount));
        }
    }

    /**
     * 이 정가에 적용한 할인액. 최소 결제 금액(100원)에 맞춰 자르는 일은 예매(DiscountCap)가 한다.
     * - FIXED: 할인액 그대로(정가와 상관없다)
     * - RATE: 정가 × 할인율 ÷ 100을 원 미만 버림한 뒤, 최대 할인액이 있으면 그 값으로 자른다
     * Note: 정률은 long으로 계산한다. 정가가 커도 넘치지 않고, 할인율이 99% 이하라 결과는 정가보다 작다.
     * 돌려주는 값(CouponDiscount.discountAmount)이 int라 int 범위로 자른다(#347).
     */
    public int discountFor(long baseAmount) {
        if (discountType == DiscountType.FIXED) {
            return discountAmount;
        }
        // 정수 나눗셈이 원 미만을 버린다(정가·할인율이 0 이상이라 내림과 같다)
        long discount = baseAmount * discountRate / 100;
        if (maxDiscountAmount != null) {
            discount = Math.min(discount, maxDiscountAmount);
        }
        return (int) Math.min(discount, Integer.MAX_VALUE);
    }

    private static void requirePositive(Integer value, String message) {
        if (value == null || value <= 0) {
            throw invalid(message);
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(CommonErrorCode.VALIDATION_ERROR, message);
    }
}
