package com.sparta.ticket.reservation.coupon.domain;

/** domain이 필요로 하는 회원 쿠폰 저장소 기능만 선언한다. 구현은 infrastructure의 UserCouponRepositoryAdapter가 맡는다. */
public interface UserCouponRepository {

    /** 이 회원이 이 쿠폰을 이미 받았는지. 발급 전에 중복을 빨리 거르는 데 쓴다. 동시 요청은 저장 때 UNIQUE가 막는다. */
    boolean existsByUserIdAndCouponId(Long userId, Long couponId);

    /**
     * 새로 발급한 회원 쿠폰을 저장한다(INSERT). 저장된 쿠폰에는 DB가 만든 ID가 채워진다.
     * 같은 회원이 같은 쿠폰을 이미 받았으면 uk_p_user_coupons_user_coupon 위반을 409 COUPON_ALREADY_ISSUED로 바꿔 던진다.
     * 예외가 트랜잭션 밖으로 나가므로 같은 트랜잭션에서 올린 발급 수량도 함께 롤백된다.
     */
    UserCoupon saveIssued(UserCoupon userCoupon);
}
