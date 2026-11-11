package com.sparta.ticket.reservation.coupon.domain;

/** 쿠폰 구분(#357). 쿠폰 목록을 나눠 보여 주는 데 쓰고, 발급 규칙(1인 1장, 기간, 수량)은 같다. */
public enum CouponCategory {
    /** 일반 쿠폰. 쿠폰 목록에서 받는다. */
    GENERAL,
    /** 웰컴 쿠폰. 웰컴 쿠폰 페이지에서 받고, 받은 날부터 7일간 쓸 수 있다(Coupon.WELCOME_VALID_DAYS). */
    WELCOME
}
