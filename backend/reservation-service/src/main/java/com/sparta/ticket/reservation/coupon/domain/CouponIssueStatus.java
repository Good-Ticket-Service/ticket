package com.sparta.ticket.reservation.coupon.domain;

/** 발급 기간으로 계산하는 쿠폰의 발급 상태. 컬럼으로 저장하지 않는다. */
public enum CouponIssueStatus {
    /** 발급 시작 전. */
    UPCOMING,
    /** 발급 기간 안. */
    ISSUING,
    /** 발급 종료 시각이 지났거나 발급을 중단했다. */
    ENDED
}
