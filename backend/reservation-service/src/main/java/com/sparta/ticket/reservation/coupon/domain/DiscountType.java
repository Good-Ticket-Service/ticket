package com.sparta.ticket.reservation.coupon.domain;

/** 쿠폰 할인 종류(#348). */
public enum DiscountType {
    /** 정액. 정해진 금액(discountAmount)을 뺀다. */
    FIXED,
    /** 정률. 정가에 할인율(discountRate, %)을 곱한 금액을 뺀다. 최대 할인액이 있으면 그 값까지만 뺀다. */
    RATE
}
