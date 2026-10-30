package com.sparta.ticket.reservation.coupon.application.facade;

/**
 * {@link CouponUsage#reserve}의 응답. 예매에 잡은 회원 쿠폰과 할인 정보다.
 * discountAmount는 발급 시점 할인액 그대로이고, name은 예매 응답·목록에 쿠폰 이름을 보여 주는 데 쓴다.
 */
public record CouponDiscount(Long userCouponId, Long couponId, String name, int discountAmount) {}
