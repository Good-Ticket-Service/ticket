package com.sparta.ticket.reservation.coupon.domain;

/** 회원 쿠폰의 상태. 발급하면 AVAILABLE이고, 나머지 전이는 예매 연동(CouponUsage)과 만료 배치가 맡는다. */
public enum UserCouponStatus {
    /** 사용할 수 있다. */
    AVAILABLE,
    /** 예매에 잡혀 있다. 결제가 끝나면 USED가 된다. */
    RESERVED,
    /** 결제가 확정돼 사용했다. */
    USED,
    /** 유효 기간이 지났다. */
    EXPIRED
}
