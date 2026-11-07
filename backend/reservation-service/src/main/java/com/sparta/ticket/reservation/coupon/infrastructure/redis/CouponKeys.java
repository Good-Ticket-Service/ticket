package com.sparta.ticket.reservation.coupon.infrastructure.redis;

/** 쿠폰 발급 판단 Redis 키 이름. 대기열(queue:...)과 섞이지 않게 "coupon:{쿠폰 ID}:{용도}" 모양으로 짓는다. */
final class CouponKeys {

    private CouponKeys() {}

    /** 정보 키(Hash). total = 총 수량, deleted = 삭제 표시(0/1). */
    static String meta(Long couponId) {
        return "coupon:" + couponId + ":meta";
    }

    /** 회원 키(Set). member는 받은 회원 ID이고, 회원 수가 곧 발급 수량이다. */
    static String users(Long couponId) {
        return "coupon:" + couponId + ":users";
    }
}
