package com.sparta.ticket.reservation.coupon.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** 쿠폰 할인 정책의 검증과 할인액 계산(#348). */
class DiscountPolicyTest {

    private static void assertError(Runnable action, ErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(errorCode);
    }

    private static DiscountPolicy rate(int rate, Integer max, Integer minBase) {
        return DiscountPolicy.of(DiscountType.RATE, null, rate, max, minBase);
    }

    @Nested
    @DisplayName("만들기")
    class Create {

        @Test
        @DisplayName("정액은 할인액만, 정률은 할인율과 선택 값(최대 할인액)으로 만든다")
        void valid() {
            DiscountPolicy fixed = DiscountPolicy.of(DiscountType.FIXED, 3_000, null, null, 30_000);
            DiscountPolicy rate = rate(10, 5_000, null);

            assertThat(fixed.getDiscountAmount()).isEqualTo(3_000);
            assertThat(fixed.getMinBaseAmount()).isEqualTo(30_000);
            assertThat(rate.getDiscountRate()).isEqualTo(10);
            assertThat(rate.getMaxDiscountAmount()).isEqualTo(5_000);
            assertThat(rate.getDiscountAmount()).isNull();
        }

        @Test
        @DisplayName("할인 종류가 없으면 VALIDATION_ERROR")
        void noType() {
            assertError(() -> DiscountPolicy.of(null, 1_000, null, null, null), CommonErrorCode.VALIDATION_ERROR);
        }

        @Test
        @DisplayName("정액인데 할인액이 없거나 0 이하면 VALIDATION_ERROR")
        void fixedWithoutAmount() {
            assertError(
                    () -> DiscountPolicy.of(DiscountType.FIXED, null, null, null, null),
                    CommonErrorCode.VALIDATION_ERROR);
            assertError(
                    () -> DiscountPolicy.of(DiscountType.FIXED, 0, null, null, null), CommonErrorCode.VALIDATION_ERROR);
        }

        @Test
        @DisplayName("정액에 할인율이나 최대 할인액을 보내면 VALIDATION_ERROR")
        void fixedWithRateFields() {
            assertError(
                    () -> DiscountPolicy.of(DiscountType.FIXED, 1_000, 10, null, null),
                    CommonErrorCode.VALIDATION_ERROR);
            assertError(
                    () -> DiscountPolicy.of(DiscountType.FIXED, 1_000, null, 5_000, null),
                    CommonErrorCode.VALIDATION_ERROR);
        }

        @Test
        @DisplayName("정률 할인율은 1~99%다. 100%는 결제 최소 금액 때문에 무료가 되지 않아 받지 않는다")
        void rateRange() {
            assertThat(rate(1, null, null).getDiscountRate()).isEqualTo(1);
            assertThat(rate(99, null, null).getDiscountRate()).isEqualTo(99);
            assertError(() -> rate(0, null, null), CommonErrorCode.VALIDATION_ERROR);
            assertError(() -> rate(100, null, null), CommonErrorCode.VALIDATION_ERROR);
            assertError(
                    () -> DiscountPolicy.of(DiscountType.RATE, null, null, null, null),
                    CommonErrorCode.VALIDATION_ERROR);
        }

        @Test
        @DisplayName("정률에 할인액을 보내거나, 최대 할인액·최소 적용 금액이 0 이하면 VALIDATION_ERROR")
        void rateInvalidOptional() {
            assertError(
                    () -> DiscountPolicy.of(DiscountType.RATE, 1_000, 10, null, null),
                    CommonErrorCode.VALIDATION_ERROR);
            assertError(() -> rate(10, 0, null), CommonErrorCode.VALIDATION_ERROR);
            assertError(() -> rate(10, null, 0), CommonErrorCode.VALIDATION_ERROR);
        }

        @Test
        @DisplayName("값이 같으면 같은 정책이다")
        void equality() {
            assertThat(rate(10, 5_000, null)).isEqualTo(rate(10, 5_000, null));
            assertThat(rate(10, 5_000, null)).isNotEqualTo(rate(10, null, null));
        }
    }

    @Nested
    @DisplayName("할인액 계산")
    class Discount {

        @Test
        @DisplayName("정액은 정가와 상관없이 할인액 그대로다")
        void fixed() {
            assertThat(DiscountPolicy.fixed(3_000).discountFor(50_000)).isEqualTo(3_000);
            assertThat(DiscountPolicy.fixed(3_000).discountFor(1_000)).isEqualTo(3_000);
        }

        @Test
        @DisplayName("정률은 정가 × 할인율이고 원 미만은 버린다")
        void rateFloor() {
            assertThat(rate(10, null, null).discountFor(40_000)).isEqualTo(4_000);
            // 12,345 × 15% = 1,851.75원 → 1,851원
            assertThat(rate(15, null, null).discountFor(12_345)).isEqualTo(1_851);
        }

        @Test
        @DisplayName("정률은 최대 할인액이 있으면 그 값까지만 뺀다")
        void rateCapped() {
            assertThat(rate(10, 5_000, null).discountFor(40_000)).isEqualTo(4_000);
            assertThat(rate(10, 5_000, null).discountFor(80_000)).isEqualTo(5_000);
        }

        @Test
        @DisplayName("정률은 정가가 커도 넘치지 않고 int 범위로 자른다")
        void rateLargeBase() {
            assertThat(rate(99, null, null).discountFor(10_000_000_000L)).isEqualTo(Integer.MAX_VALUE);
        }
    }

    @Nested
    @DisplayName("최소 적용 금액")
    class MinBase {

        @Test
        @DisplayName("없으면 정가와 상관없이 쓸 수 있다")
        void none() {
            assertThat(DiscountPolicy.fixed(3_000).isApplicableTo(1)).isTrue();
        }

        @Test
        @DisplayName("정가가 최소 적용 금액 이상이면 쓸 수 있다(같은 금액 포함)")
        void enough() {
            DiscountPolicy policy = rate(10, null, 30_000);

            assertThat(policy.isApplicableTo(30_000)).isTrue();
            assertThat(policy.isApplicableTo(29_999)).isFalse();
        }

        @Test
        @DisplayName("모자라면 COUPON_NOT_AVAILABLE이고 메시지에 최소 적용 금액이 들어간다")
        void notEnough() {
            DiscountPolicy policy = rate(10, null, 30_000);

            assertThatThrownBy(() -> policy.requireApplicableTo(20_000))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("30,000원 이상 결제 시 사용할 수 있는 쿠폰입니다.")
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CouponErrorCode.COUPON_NOT_AVAILABLE);
        }
    }
}
