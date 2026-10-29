package com.sparta.ticket.reservation.booking.domain;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.domain.ReservationErrorCode;

/**
 * 쿠폰 할인액을 결제 금액이 최소 결제 금액 아래로 내려가지 않게 자른다. 쿠폰은 정가를 모르므로 booking이 자른다.
 * 결제는 받은 할인액을 고치지 않고 검증만 하므로, 여기서 자르지 않으면 결제 준비가 거절된다.
 */
public final class DiscountCap {

    /** 결제가 받는 가장 작은 금액. 결제의 금액 검증과 같은 값이다. */
    public static final long MIN_PAYMENT_AMOUNT = 100;

    private DiscountCap() {}

    /** 정가가 최소 결제 금액 이하면 할인할 여지가 없으므로 쿠폰을 쓸 수 없다. */
    public static long of(long baseAmount, long couponAmount) {
        if (baseAmount <= MIN_PAYMENT_AMOUNT) {
            throw new BusinessException(ReservationErrorCode.COUPON_NOT_AVAILABLE, "결제 금액이 너무 적어 쿠폰을 쓸 수 없습니다.");
        }
        return Math.min(couponAmount, baseAmount - MIN_PAYMENT_AMOUNT);
    }
}
