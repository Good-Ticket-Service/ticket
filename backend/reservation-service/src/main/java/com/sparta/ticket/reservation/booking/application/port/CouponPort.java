package com.sparta.ticket.reservation.booking.application.port;

/**
 * booking이 coupon 모듈에 바라는 쿠폰 연산(CouponUsage, #125). 모두 호출한 쪽의 트랜잭션에 합류하므로,
 * 예약 전이가 롤백되면 쿠폰 상태도 함께 롤백된다.
 * 잠금 순서는 좌석 → 예약 → 쿠폰이다. 예약을 바꾸는 트랜잭션은 예약의 조건부 UPDATE를 쿠폰 호출보다 먼저 한다.
 */
public interface CouponPort {

    /**
     * 회원 쿠폰을 이 예약에 잡고 발급 시점의 할인액을 돌려준다. 최소 결제 금액에 맞춰 자르는 일은 booking이 한다.
     * 본인 쿠폰이 아니거나, 쓸 수 있는 상태가 아니거나, 유효 기간이 지났으면 COUPON_NOT_AVAILABLE 예외를 던진다.
     * 이 예약에 다른 쿠폰이 잡혀 있으면 예외를 던지므로, 쿠폰을 바꿀 때는 release를 먼저 부른다.
     */
    long reserve(Long userCouponId, Long userId, Long reservationId);

    /** 결제가 확정된 예약의 쿠폰을 사용 처리한다. 잡힌 쿠폰이 없으면 아무것도 하지 않는다. */
    void use(Long reservationId);

    /** 예약에 잡힌 쿠폰을 돌려준다. 잡힌 쿠폰이 없거나 이미 돌려줬어도 성공한다. */
    void release(Long reservationId);
}
