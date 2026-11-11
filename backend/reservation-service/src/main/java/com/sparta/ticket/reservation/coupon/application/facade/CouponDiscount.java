package com.sparta.ticket.reservation.coupon.application.facade;

/**
 * {@link CouponUsage#reserve}의 응답. 예매에 잡은 회원 쿠폰과 할인 정보다.
 * discountAmount는 reserve에 넘긴 정가에 발급 시점 할인 정책을 적용한 할인액이고(#347), name은 예매 응답·목록에 쿠폰 이름을 보여 주는 데 쓴다.
 */
public record CouponDiscount(Long userCouponId, Long couponId, String name, int discountAmount) {}
